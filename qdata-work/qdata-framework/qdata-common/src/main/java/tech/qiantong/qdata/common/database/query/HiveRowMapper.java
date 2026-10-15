package tech.qiantong.qdata.common.database.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Hive DATE/TIMESTAMP are business wall-clock values, without a time zone. */
public class HiveRowMapper extends MyRowMapper {
    @Override
    protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
        int type = rs.getMetaData().getColumnType(index);
        if (type == Types.TIMESTAMP || type == Types.DATE) {
            // Read the driver's text before Jackson can treat java.sql.Timestamp
            // as an instant and shift it into the web server's response time zone.
            // Keep SQL NULL and the full fractional-second precision unchanged.
            return rs.getString(index);
        }
        return super.getColumnValue(rs, index);
    }
}
