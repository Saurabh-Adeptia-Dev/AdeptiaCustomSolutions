package custom.beans;

import com.adeptia.indigo.api.custom.ExecutionEvent;
import com.adeptia.indigo.api.custom.ScriptGuardExecutor;
import com.adeptia.indigo.system.Context;
import org.springframework.stereotype.Component;

@Component("RecordCheckBean")
public class RecordCheckBean implements ScriptGuardExecutor {

    @Override
    public boolean execute(ExecutionEvent executionEvent) {
        Context context = executionEvent.getContext();
        Boolean processRecord = (Boolean) context.get("processRecord");
        if (processRecord == null)
            return false;
        else
            return processRecord;
    }
}
