package tech.qiantong.qdata.common.database.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Exact display values for interactive query/preview endpoints; not a global API serializer. */
public final class BrowserQueryValues {
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private static final BigInteger MAX_SAFE_BIG_INTEGER=BigInteger.valueOf(MAX_SAFE_INTEGER);
    private static final BigDecimal MAX_SAFE_DECIMAL=BigDecimal.valueOf(MAX_SAFE_INTEGER);
    private BrowserQueryValues() {}
    public static Object convert(Object value) {
        if(value instanceof BigDecimal decimal){
            double candidate=decimal.doubleValue();
            return decimal.abs().compareTo(MAX_SAFE_DECIMAL)>0 || !Double.isFinite(candidate) || BigDecimal.valueOf(candidate).compareTo(decimal)!=0
                    ?decimalText(decimal):decimal;
        }
        if(value instanceof BigInteger integer)return integer.abs().compareTo(MAX_SAFE_BIG_INTEGER)>0?integer.toString():integer;
        if(value instanceof Long integer)return integer>MAX_SAFE_INTEGER || integer<-MAX_SAFE_INTEGER?integer.toString():integer;
        if(value instanceof Double number && !Double.isFinite(number))return number.toString();
        if(value instanceof Float number && !Float.isFinite(number))return number.toString();
        if(value instanceof PageResult<?> page){
            List<?> rows=page.getData()==null?null:page.getData().stream().map(BrowserQueryValues::convert).toList();
            return new PageResult<>(page.getTotal(),rows).setPageNum(page.getPageNum()).setPageSize(page.getPageSize());
        }
        if(value instanceof Map<?,?> map){Map<Object,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(key,convert(item)));return result;}
        if(value instanceof Collection<?> rows)return rows.stream().map(BrowserQueryValues::convert).toList();
        return value;
    }
    public static String decimalText(BigDecimal value){
        // Scientific notation remains exact when a pathological exponent would expand huge text.
        return Math.abs((long)value.scale())>1000?value.toString():value.toPlainString();
    }
}
