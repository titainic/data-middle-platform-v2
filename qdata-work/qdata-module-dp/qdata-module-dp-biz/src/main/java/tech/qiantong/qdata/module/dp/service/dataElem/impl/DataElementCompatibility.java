package tech.qiantong.qdata.module.dp.service.dataElem.impl;

import java.util.Locale;

final class DataElementCompatibility {
    static boolean compatible(String standard,String physical) {
        if(standard==null || physical==null)return false;
        String expected=normalize(standard),actual=normalize(physical);
        if(expected.contains("("))return expected.equals(actual);
        int parameters=actual.indexOf('(');
        return expected.equals(parameters<0?actual:actual.substring(0,parameters));
    }
    private static String normalize(String type) {
        String value=type.replaceAll("\\s+","").toUpperCase(Locale.ROOT);
        if(value.equals("STRING"))return "VARCHAR";
        if(value.equals("INTEGER"))return "INT";
        if(value.startsWith("NUMERIC"))return value.replaceFirst("NUMERIC","DECIMAL");
        return value;
    }
}
