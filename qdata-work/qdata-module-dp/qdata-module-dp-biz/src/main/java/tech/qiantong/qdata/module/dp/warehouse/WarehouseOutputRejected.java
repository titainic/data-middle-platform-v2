package tech.qiantong.qdata.module.dp.warehouse;

/** Hive output exists and was read successfully, but its declared keys are invalid. */
final class WarehouseOutputRejected extends IllegalArgumentException {
    WarehouseOutputRejected(String message){super(message);}
}
