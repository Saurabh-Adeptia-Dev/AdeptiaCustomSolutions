package custom.beans;

import com.adeptia.indigo.api.custom.ExecutionEvent;
import com.adeptia.indigo.api.custom.ScriptExecutor;
import com.adeptia.indigo.logging.LogDatabaseConnection;
import com.adeptia.indigo.system.Context;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Component("LogDatabaseTableRecordCount")
public class LogDatabaseTableRecordCount implements ScriptExecutor{
    @Override
    public void execute(ExecutionEvent executionEvent) {
        Context context = executionEvent.getContext();
        int count = 0;
        String tableName = (String)context.get("tableName");
        String query = "SELECT COUNT(*) FROM " + tableName;
        try {
            Connection logConnection = LogDatabaseConnection.getConection();
            PreparedStatement pstmt = logConnection.prepareStatement(query);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                count = rs.getInt(1);
            }
            context.put("LogTableRecordCount",String.valueOf(count));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
