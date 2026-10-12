package tech.qiantong.qdata.common.utils;

import com.alibaba.fastjson2.JSON;
import java.util.*;
import java.util.regex.Pattern;

/** Remove credentials from log and read-only configuration presentations. */
public final class SecretRedactor {
    private SecretRedactor() { }
    private static final String MASK="[REDACTED]";
    private static final Set<String> KEYS=Set.of("password","passwd","pwd","secret","clientsecret","accesskey","secretkey","accesstoken","refreshtoken","token","apikey","authorization","cookie","setcookie","privatekey","clienttoken");
    private static final Pattern AUTH=Pattern.compile("(?i)(authorization[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:bearer|basic)\\s+[^\\s,;\\\"'}]+ ".trim());
    private static final Pattern PAIR=Pattern.compile("(?i)((?:password|passwd|pwd|secret|client[_-]?secret|access[_-]?token|refresh[_-]?token|api[_-]?key|token)[\\\"']?\\s*[:=]\\s*)(\\\"(?:\\\\.|[^\\\"])*\\\"|'[^']*'|[^\\s,;&}\\]]+)");
    private static final Pattern USERINFO=Pattern.compile("(//[^/@:\\s]+:)[^@/\\s]+@");
    public static String redact(String value){
        if(value==null || value.isBlank())return value;
        try{Object parsed=JSON.parse(value);if(parsed instanceof Map<?,?> || parsed instanceof Collection<?>)return JSON.toJSONString(clean(parsed,0));}catch(RuntimeException ignored){}
        return text(value);
    }
    private static boolean secret(String key){return key!=null && KEYS.contains(key.replace("_","").replace("-","").toLowerCase(Locale.ROOT));}
    private static Object clean(Object value,int depth){
        if(depth>30)return MASK;
        if(value instanceof Map<?,?> map){
            boolean credentialParameter=List.of("paramName","name","headerName","fieldName").stream().anyMatch(key->secret(Objects.toString(map.get(key),null)));
            Map<String,Object> result=new LinkedHashMap<>();map.forEach((k,v)->{String key=String.valueOf(k);boolean parameterValue=credentialParameter && Set.of("value","paramValue","defaultValue","content","example","fieldValue").contains(key);result.put(key,secret(key)||parameterValue?MASK:clean(v,depth+1));});return result;
        }
        if(value instanceof Collection<?> values)return values.stream().map(item->clean(item,depth+1)).toList();
        if(value instanceof String string){
            if(string.stripLeading().startsWith("{") || string.stripLeading().startsWith("["))try{Object parsed=JSON.parse(string);if(parsed instanceof Map<?,?> || parsed instanceof Collection<?>)return JSON.toJSONString(clean(parsed,depth+1));}catch(RuntimeException ignored){}
            return text(string);
        }
        return value;
    }
    private static String text(String value){return USERINFO.matcher(PAIR.matcher(AUTH.matcher(value).replaceAll("$1"+MASK)).replaceAll("$1"+MASK)).replaceAll("$1"+MASK+"@");}
}
