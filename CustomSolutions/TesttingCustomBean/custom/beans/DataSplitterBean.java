package custom.beans;

import com.adeptia.indigo.api.custom.ExecutionEvent;
import com.adeptia.indigo.api.custom.ScriptExecutor;
import com.adeptia.indigo.services.ServiceException;
import com.adeptia.indigo.services.transform.ScriptedService;
import com.adeptia.indigo.services.webservice.DataSplitter;
import com.adeptia.indigo.system.Context;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

@Component("DataSplitterBean")
public class DataSplitterBean implements ScriptExecutor {

    @Override
    public void execute(ExecutionEvent executionEvent){

        Context context = executionEvent.getContext();
        ScriptedService service = (ScriptedService) executionEvent.getService();
        InputStream inputStream = null;
        OutputStream outputStream = null;

        try {
            inputStream = executionEvent.getInputStream();
            outputStream = getOutputStreamFromService(service);
            boolean processRecord = true;

            // getValueByName exists only in Runtime ScriptedService — invoke via reflection
            int queueSize = Integer.parseInt(getValueByName(service, "splitSize"));
            String sourceXPath = getValueByName(service, "sourceXPath");
            String characterSetEncoding = service.getEncodingUsed();

            // startPosition holds the next batch's start record (1-indexed).
            Integer startPosition = (Integer) context.get("startPosition");
            if (startPosition == null) {
                startPosition = 1;
            }

            Integer totalRecordCount = (Integer) context.get("totalRecordCount");
            DataSplitter handler = new DataSplitter();

            if (totalRecordCount == null) {
                // First invocation: splitData returns total record count across entire input.
                totalRecordCount = handler.splitData(inputStream, outputStream,
                        startPosition, queueSize, sourceXPath,
                        characterSetEncoding, new HashMap<>(), false);
                if (totalRecordCount <= 0) {
                    processRecord = false;
                } else {
                    startPosition += queueSize;  // advance to next batch start
                }
            } else {
                totalRecordCount -= queueSize;
                if (totalRecordCount <= 0) {
                    processRecord = false;
                } else {
                    // startPosition already points to the next unprocessed batch.
                    handler.splitData(inputStream, outputStream,
                            startPosition, queueSize, sourceXPath,
                            characterSetEncoding, new HashMap<>(), false);
                    startPosition += queueSize;  // advance to next batch start
                }
            }

            context.put("processRecord", processRecord);
            context.put("totalRecordCount", totalRecordCount);
            context.put("startPosition", startPosition);

        } catch (Exception e) {
            try {
                throw new ServiceException(e);
            } catch (ServiceException ex) {
                throw new RuntimeException(ex);
            }
        } finally {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // getValueByName is defined in Runtime's ScriptedService, not in Commons.
    // Invoked via reflection so this bean can compile in the Commons module.
    private String getValueByName(ScriptedService service, String name) throws Exception {
        return (String) service.getClass()
                .getMethod("getValueByName", String.class)
                .invoke(service, name);
    }

    // getOutputStream() is defined in Runtime's AbstractTransformer, not in Commons.
    // The framework never sets outputStream on ExecutionEvent for Spring Bean executors,
    // so the stream must be fetched directly from the service.
    private OutputStream getOutputStreamFromService(ScriptedService service) throws Exception {
        return (OutputStream) service.getClass()
                .getMethod("getOutputStream")
                .invoke(service);
    }
}
