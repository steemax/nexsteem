/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Safe data-only reader for gem index tuples. It never instantiates Ruby classes. */
final class RubyMarshal {
  private final byte[] data;
  private int position;
  private final List<Object> objects=new ArrayList<>();
  private final List<String> symbols=new ArrayList<>();
  private RubyMarshal(byte[] data) { this.data=data; }
  static List<RubygemsCodec.GemMetadata> specs(byte[] data) {
    Object root=decode(data);
    if(!(root instanceof List<?> list))throw new IllegalArgumentException("Invalid gem specs root");
    var result=new ArrayList<RubygemsCodec.GemMetadata>();
    for(Object row:list) {
      if(!(row instanceof List<?> fields)||fields.size()!=3)throw new IllegalArgumentException("Invalid gem spec tuple");
      String name=string(fields.get(0)),version=version(fields.get(1)),platform=string(fields.get(2));
      RubygemsProtocol.validateIdentity(name,version,platform);
      result.add(new RubygemsCodec.GemMetadata(name,version,platform,List.of()));
    }
    return result;
  }
  static Object decode(byte[] data) {
    if(data.length>64*1024*1024)throw new IllegalArgumentException("Gem index exceeds 64 MiB");
    var reader=new RubyMarshal(data);
    if(reader.next()!=4||reader.next()!=8)throw new IllegalArgumentException("Unsupported Ruby Marshal version");
    Object root=reader.read(0);
    if(reader.position!=data.length)throw new IllegalArgumentException("Trailing Ruby Marshal data");
    return root;
  }
  private Object read(int depth) {
    if(depth>64||objects.size()>500000||symbols.size()>10000)throw new IllegalArgumentException("Ruby Marshal complexity limit");
    return switch(next()) {
      case '0' -> null;
      case 'T' -> Boolean.TRUE;
      case 'F' -> Boolean.FALSE;
      case 'i' -> integer();
      case ':' -> {String symbol=bytes();symbols.add(symbol);yield symbol;}
      case ';' -> symbols.get(index(symbols.size()));
      case '@' -> objects.get(index(objects.size()));
      case '"' -> {String value=bytes();objects.add(value);yield value;}
      case '[' -> {int size=size();var list=new ArrayList<>();objects.add(list);for(int i=0;i<size;i++)list.add(read(depth+1));yield list;}
      case '{' -> {int size=size();var map=new LinkedHashMap<>();objects.add(map);for(int i=0;i<size;i++)map.put(read(depth+1),read(depth+1));yield map;}
      case 'I' -> {Object value=read(depth+1);int size=size();for(int i=0;i<size;i++){read(depth+1);read(depth+1);}yield value;}
      case 'o' -> {String type=string(read(depth+1));requireVersion(type);var map=new LinkedHashMap<String,Object>();map.put("__class",type);objects.add(map);int size=size();for(int i=0;i<size;i++)map.put(string(read(depth+1)),read(depth+1));yield map;}
      case 'U' -> {String type=string(read(depth+1));requireVersion(type);var map=new LinkedHashMap<String,Object>();map.put("__class",type);objects.add(map);map.put("__data",read(depth+1));yield map;}
      case 'u' -> {String type=string(read(depth+1));requireVersion(type);var map=new LinkedHashMap<String,Object>();objects.add(map);map.put("__data",bytes());yield map;}
      default -> throw new IllegalArgumentException("Unsupported Ruby Marshal index value");
    };
  }
  private int integer() {
    int length=(byte)next();if(length==0)return 0;if(length>4)return length-5;if(length< -4)return length+5;
    int result=length<0?-1:0;
    for(int i=0;i<Math.abs(length);i++){int value=next();int mask=255<<(8*i);result=(result&~mask)|(value<<(8*i));}
    return result;
  }
  private int size() {int size=integer();if(size<0||size>500000)throw new IllegalArgumentException("Invalid Ruby Marshal length");return size;}
  private int index(int limit) {int index=integer();if(index<0||index>=limit)throw new IllegalArgumentException("Invalid Ruby Marshal reference");return index;}
  private String bytes() {int length=size();if(length>data.length-position)throw new IllegalArgumentException("Truncated Ruby Marshal string");String value=new String(data,position,length,StandardCharsets.UTF_8);position+=length;return value;}
  private int next() {if(position>=data.length)throw new IllegalArgumentException("Truncated Ruby Marshal data");return data[position++]&255;}
  private static String string(Object value) {if(!(value instanceof String text))throw new IllegalArgumentException("Expected Ruby Marshal string");return text;}
  private static void requireVersion(String type) {if(!type.equals("Gem::Version"))throw new IllegalArgumentException("Only Gem::Version data is accepted");}
  private static String version(Object value) {
    if(value instanceof String text)return text;
    if(value instanceof Map<?,?> map){if(map.containsKey("@version"))return version(map.get("@version"));return version(map.get("__data"));}
    if(value instanceof List<?> list&&!list.isEmpty())return version(list.get(0));
    throw new IllegalArgumentException("Invalid Gem::Version data");
  }
}
