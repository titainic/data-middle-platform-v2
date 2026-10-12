package tech.qiantong.qdata.module.dp.warehouse;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Response-only clock contract: warehouse SQL metadata is UTC; IoT intake instances are UTC+08. */
final class WarehouseApiTime {
    private WarehouseApiTime() {}
    static Object metadata(Object value) {
        return copy(value,Set.of("created_at","updated_at","started_at","finished_at"),ZoneOffset.UTC);
    }
    static Object intake(Object value) {
        return copy(value,Set.of("start_time","end_time"),ZoneOffset.ofHours(8));
    }
    private static Object copy(Object value,Set<String> fields,ZoneOffset offset) {
        if(value instanceof Map<?,?> map) {
            Map<Object,Object> result=new LinkedHashMap<>();
            map.forEach((key,item)->result.put(key,fields.contains(key) && item instanceof LocalDateTime time
                    ?time.atOffset(offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME):copy(item,fields,offset)));
            return result;
        }
        if(value instanceof Collection<?> collection)return collection.stream().map(item->copy(item,fields,offset)).toList();
        return value;
    }
}
