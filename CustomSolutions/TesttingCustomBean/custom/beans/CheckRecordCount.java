package custom.beans;

import com.adeptia.indigo.api.custom.ExecutionEvent;
import com.adeptia.indigo.api.custom.ScriptGuardExecutor;
import com.adeptia.indigo.system.Context;
import org.springframework.stereotype.Component;


@Component("CheckRecordCount")
public class CheckRecordCount implements ScriptGuardExecutor {
    @Override
    public boolean execute(ExecutionEvent executionEvent) {

        Context context = executionEvent.getContext();
        String recordCount = (String)context.get("LogTableRecordCount");
        if(recordCount == null){
            context.put("RecordCountFetchStatus","false");
            return true;
        }
        else{
            context.put("RecordCountFetchStatus","true");
            return false;
        }

    }
}
