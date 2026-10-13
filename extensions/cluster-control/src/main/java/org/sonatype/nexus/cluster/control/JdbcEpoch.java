/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import java.util.LinkedHashMap;
/** Pure JDBC URL binding shared by standalone supervisor and the Core configuration source. */
final class JdbcEpoch {
  static String bind(String url,String cluster,String owner,long epoch) throws Exception {
    if(!cluster.matches("[A-Za-z0-9_.-]{1,80}")||!owner.matches("[A-Za-z0-9_.:-]{1,250}")||epoch<1)throw new IllegalArgumentException("Invalid fixed SQL identity");
    int query=url.indexOf('?');String base=query<0?url:url.substring(0,query);String existing=query<0?"":url.substring(query+1);
    var values=new LinkedHashMap<String,String>();String previous="";
    for(String entry:existing.split("&")){if(entry.isEmpty())continue;String[] pair=entry.split("=",2);String key=java.net.URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8),value=pair.length>1?java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8):"";
      if(key.equals("options"))previous=value;else if(!key.equalsIgnoreCase("ApplicationName"))values.put(key,value);}
    String options=previous+" -c nexus.cluster.id="+cluster+" -c nexus.cluster.owner="+owner+" -c nexus.cluster.epoch="+epoch;
    var md5=java.security.MessageDigest.getInstance("MD5");String c=java.util.HexFormat.of().formatHex(md5.digest(cluster.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,12),o=java.util.HexFormat.of().formatHex(md5.digest(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,16);
    values.put("options",options.strip());values.put("ApplicationName","nxcore:"+c+":"+o+":"+epoch);values.put("targetServerType","primary");
    return base+"?"+values.entrySet().stream().map(e->java.net.URLEncoder.encode(e.getKey(),java.nio.charset.StandardCharsets.UTF_8)+"="+java.net.URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
  }
}
