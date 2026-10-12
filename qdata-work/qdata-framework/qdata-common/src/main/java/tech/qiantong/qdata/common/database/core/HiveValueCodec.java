package tech.qiantong.qdata.common.database.core;

import java.math.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reject lossy/invalid JDBC values before Hive's CAST can silently turn them into NULL. */
public final class HiveValueCodec {
    private HiveValueCodec() {}
    private static final Pattern DECIMAL=Pattern.compile("DECIMAL\\(([0-9]+),([0-9]+)\\)");
    private static final Pattern TEXT=Pattern.compile("(CHAR|VARCHAR)\\(([0-9]+)\\)");
    private static final DateTimeFormatter TIMESTAMP=new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(' ').appendPattern("HH:mm:ss").optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND,1,9,true).optionalEnd()
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    public static Object convert(Object value,String targetType) {
        if(value==null)return null;
        String type=targetType==null?"":targetType.toUpperCase(Locale.ROOT).replaceAll("\\s+","");
        try {
            String text=value instanceof Boolean?((Boolean)value?"1":"0"):value.toString().trim();
            var decimal=DECIMAL.matcher(type);
            if(decimal.matches()){
                int precision=Integer.parseInt(decimal.group(1)),scale=Integer.parseInt(decimal.group(2));
                if(precision<1 || precision>38 || scale<0 || scale>precision)throw new IllegalArgumentException();
                BigDecimal number=decimal(text);
                if(number.signum()==0)return BigDecimal.ZERO.setScale(scale);
                long integerDigits=(long)number.precision()-number.scale();
                if(integerDigits>precision-scale)throw new IllegalArgumentException();
                if(integerDigits-1 < -((long)scale)-1)return BigDecimal.ZERO.setScale(scale);
                number=number.setScale(scale,RoundingMode.HALF_UP);
                if(number.precision()>precision)throw new IllegalArgumentException();return number;
            }
            var boundedText=TEXT.matcher(type);
            if(boundedText.matches()){
                String result=string(value);int length=Integer.parseInt(boundedText.group(2));
                if(length<1 || length>(boundedText.group(1).equals("CHAR")?255:65535) || result.codePointCount(0,result.length())>length)throw new IllegalArgumentException();return result;
            }
            switch(type){
                case "STRING":return string(value);
                case "TINYINT":return integer(text).byteValueExact();
                case "SMALLINT":return integer(text).shortValueExact();
                case "INT":return integer(text).intValueExact();
                case "BIGINT":return integer(text).longValueExact();
                case "FLOAT":{if(text.length()>512)throw new IllegalArgumentException();float number=Float.parseFloat(text);if(!Float.isFinite(number) || (number==0 && decimal(text).signum()!=0))throw new IllegalArgumentException();return number;}
                case "DOUBLE":{if(text.length()>512)throw new IllegalArgumentException();double number=Double.parseDouble(text);if(!Double.isFinite(number) || (number==0 && decimal(text).signum()!=0))throw new IllegalArgumentException();return number;}
                case "BOOLEAN":{
                    if(value instanceof Boolean)return value;
                    if(text.equalsIgnoreCase("true") || text.equals("1"))return true;
                    if(text.equalsIgnoreCase("false") || text.equals("0"))return false;
                    throw new IllegalArgumentException();
                }
                case "DATE":{
                    if(value instanceof java.sql.Date)return value;
                    if(value instanceof LocalDate)return java.sql.Date.valueOf((LocalDate)value);
                    if(value instanceof java.sql.Timestamp)return java.sql.Date.valueOf(((java.sql.Timestamp)value).toLocalDateTime().toLocalDate());
                    if(value instanceof LocalDateTime)return java.sql.Date.valueOf(((LocalDateTime)value).toLocalDate());
                    if(text.length()>10)return java.sql.Date.valueOf(LocalDateTime.parse(text.replace('T',' '),TIMESTAMP).toLocalDate());
                    return java.sql.Date.valueOf(LocalDate.parse(text,DateTimeFormatter.ISO_LOCAL_DATE));
                }
                case "TIMESTAMP":{
                    if(value instanceof java.sql.Timestamp)return value;
                    if(value instanceof LocalDateTime)return java.sql.Timestamp.valueOf((LocalDateTime)value);
                    if(value instanceof Instant)return java.sql.Timestamp.from((Instant)value);
                    if(text.length()==10)return java.sql.Timestamp.valueOf(LocalDate.parse(text,DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay());
                    return java.sql.Timestamp.valueOf(LocalDateTime.parse(text.replace('T',' '),TIMESTAMP));
                }
                default:throw new IllegalArgumentException();
            }
        }catch(RuntimeException failure){throw new IllegalArgumentException("值不符合Hive目标类型："+type);}
    }
    private static BigDecimal decimal(String text){if(text.length()>512)throw new IllegalArgumentException();return new BigDecimal(text);}
    private static BigInteger integer(String text){
        BigDecimal number=decimal(text);if(number.signum()==0)return BigInteger.ZERO;number=number.stripTrailingZeros();
        if(number.scale()>0 || (long)number.precision()-number.scale()>19)throw new IllegalArgumentException();return number.toBigIntegerExact();
    }
    private static String string(Object value){
        if(value instanceof CharSequence || value instanceof Character || value instanceof Number || value instanceof Boolean
                || value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor)return value.toString();
        throw new IllegalArgumentException();
    }
}
