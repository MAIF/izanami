package izanami.example.configuration;


import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import org.reactivecouchbase.json.JsValue;
import org.reactivecouchbase.json.Json;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class JsonMessageConverter implements HttpMessageConverter<JsValue> {

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return JsValue.class.isAssignableFrom(clazz) && (mediaType.equals(MediaType.APPLICATION_JSON) || mediaType.equals(MediaType.APPLICATION_JSON_UTF8));
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return JsValue.class.isAssignableFrom(clazz) && (mediaType.equals(MediaType.APPLICATION_JSON) || mediaType.equals(MediaType.APPLICATION_JSON_UTF8));
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        List<MediaType> types = new ArrayList<>();
        types.add(MediaType.APPLICATION_JSON);
        types.add(MediaType.APPLICATION_JSON_UTF8);
        return types;
    }

    @Override
    public JsValue read(Class<? extends JsValue> clazz, HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        // Blocks request here ... maybe this class is not useful at all
        String body = CharStreams.toString(new InputStreamReader(inputMessage.getBody(), Charsets.UTF_8));
        return Json.parse(body);
    }

    @Override
    public void write(JsValue jsValue, MediaType contentType, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        outputMessage.getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        outputMessage.getBody().write(Json.stringify(jsValue).getBytes("UTF-8"));
    }
}