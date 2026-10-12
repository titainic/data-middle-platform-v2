package tech.qiantong.qdata.module.dp.warehouse;

/** A read-only data preflight rejected input before the INSERT request. */
final class WarehouseInputRejected extends IllegalArgumentException {
    WarehouseInputRejected(String message,Throwable cause){super(message,cause);}
}
