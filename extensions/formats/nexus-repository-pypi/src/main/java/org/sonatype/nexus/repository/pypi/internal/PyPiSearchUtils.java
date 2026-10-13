/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.pypi.internal;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.xpath.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import com.google.common.base.Strings;
import static java.util.Objects.requireNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import org.sonatype.nexus.common.io.SafeXml;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.pypi.datastore.PypiContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.common.entity.Continuation;
public final class PyPiSearchUtils {
 public record Term(String field, Pattern pattern) {}
 public record Query(String repository, List<Term> terms) {}
 public static Query parseSearchRequest(String repository, InputStream input) throws Exception {
  Document document=SafeXml.newdocumentBuilderFactory().newDocumentBuilder().parse(input);
  XPath x=XPathFactory.newInstance().newXPath();
  if(!"search".equals(x.evaluate("/methodCall/methodName",document))) throw new IllegalArgumentException("Only search methods supported");
  String op=x.evaluate("/methodCall/params/param/value/string",document);
  if(!"or".equals(op)) throw new IllegalArgumentException("Only or-search operations supported");
  NodeList members=(NodeList)x.evaluate("/methodCall/params/param/value/struct/member",document,XPathConstants.NODESET);
  List<Term> terms=new ArrayList<>();
  for(int i=0;i<members.getLength();i++) {
   Node member=members.item(i);String field=x.evaluate("name",member);
   if(!Set.of("name","summary").contains(field)) throw new IllegalArgumentException("Unsupported search field");
   NodeList values=(NodeList)x.evaluate("value/array/data/value/string",member,XPathConstants.NODESET);
   for(int j=0;j<values.getLength();j++) {
    String v=values.item(j).getTextContent().toLowerCase(Locale.ROOT);
    if(v.length()>1024||terms.size()>=100)throw new IllegalArgumentException("Search request too large");
    String regex=Arrays.stream(v.split("\\*",-1)).map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
    if(!v.contains("*"))regex=".*"+regex+".*";
    terms.add(new Term(field,Pattern.compile(regex,Pattern.DOTALL)));
   }
  }
  return new Query(repository,List.copyOf(terms));
 }
 public static List<PyPiSearchResult> pypiSearch(Query query, RepositoryManager manager) {
  PypiContentFacet facet=requireNonNull(manager.get(query.repository())).facet(PypiContentFacet.class);
  List<PyPiSearchResult> results=new ArrayList<>();String token=null;
  do {
   Continuation<FluentComponent> page=facet.components().browse(1000,token);
   for(FluentComponent c:page) {
    String summary=c.attributes().child(PyPiFormat.NAME).get(PyPiAttributes.P_SUMMARY,String.class,"");
    if(query.terms().stream().anyMatch(t->t.pattern().matcher((t.field().equals("name")?c.name():summary).toLowerCase(Locale.ROOT)).matches())) {
     results.add(new PyPiSearchResult(c.name(),c.version(),summary));
    }
   }
   token=page.isEmpty()?null:page.nextContinuationToken();
  }while(token!=null);
  return results;
 }
  public static String buildSearchResponse(final Collection<PyPiSearchResult> results) throws Exception {
    checkNotNull(results);
    StringWriter s = new StringWriter();
    XMLOutputFactory factory = XMLOutputFactory.newInstance();
    try (SearchResponseWriter writer = new SearchResponseWriter(factory.createXMLStreamWriter(s))) {
      writer.writePrologue();
      for (PyPiSearchResult result : results) {
        writer.writeEntry(result);
      }
      writer.writeEpilogue();
    }
    return s.toString();
  }

  /**
   * Parses an XML-RPC search response into a list of search results (such as for merging from different repositories).
   */
  static List<PyPiSearchResult> parseSearchResponse(final InputStream in) throws Exception {
    SAXParserFactory factory = SafeXml.newSaxParserFactory();
    factory.setValidating(false);
    factory.setNamespaceAware(false);

    SearchResponseHandler handler = new SearchResponseHandler();
    SAXParser parser = factory.newSAXParser();
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    parser.parse(in, handler);
    return handler.getResults();
  }

  /**
   * SAX handler to extract result information from a PyPI XML-RPC search result.
   */
  private static class SearchResponseHandler
      extends DefaultHandler
  {
    private List<PyPiSearchResult> results = new ArrayList<>();

    private String currentName = null;

    private String currentVersion = null;

    private String currentSummary = null;

    private String currentMemberName = null;

    private String currentMemberValue = null;

    private StringBuilder currentCharacters = null;

    private boolean fault = false;

    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
      if (fault) {
        return;
      }
      switch (qName) {
        case "fault":
          fault = true;
          break;
        case "member":
          currentMemberName = null;
          currentMemberValue = null;
          break;
        case "name":
          currentCharacters = new StringBuilder();
          break;
        case "string":
          currentCharacters = new StringBuilder();
          break;
        case "boolean":
          currentCharacters = new StringBuilder();
          break;
        default:
          // empty
      }
    }

    public void endElement(String uri, String localName, String qName) throws SAXException
    {
      if (fault) {
        return;
      }
      switch (qName) {
        case "struct":
          results.add(buildSearchResult());
          currentName = null;
          currentVersion = null;
          currentSummary = null;
          break;
        case "member":
          store(currentMemberName, currentMemberValue);
          currentMemberName = null;
          currentMemberValue = null;
          break;
        case "name":
          currentMemberName = currentCharacters.toString();
          currentCharacters = null;
          break;
        case "string":
          currentMemberValue = currentCharacters.toString();
          currentCharacters = null;
          break;
        case "boolean":
          currentMemberValue = currentCharacters.toString();
          currentCharacters = null;
          break;
        default:
          // empty
      }
    }

    public void characters(char[] ch, int start, int length) throws SAXException {
      if (!fault && currentCharacters != null) {
        currentCharacters.append(ch, start, length);
      }
    }

    private void store(String name, String value) {
      switch(name) {
        case "name":
          currentName = value;
          break;
        case "version":
          currentVersion = value;
          break;
        case "summary":
          currentSummary = value;
          break;
        default:
          // empty
      }
    }

    private PyPiSearchResult buildSearchResult() {
      return new PyPiSearchResult(
          Strings.nullToEmpty(currentName),
          Strings.nullToEmpty(currentVersion),
          Strings.nullToEmpty(currentSummary));
    }

    public List<PyPiSearchResult> getResults() {
      return results;
    }
  }

  private PyPiSearchUtils() {
    // empty
  }
}
