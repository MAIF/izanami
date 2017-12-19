package izanami.example.controller;


import io.vavr.control.Option;
import izanami.javadsl.Proxy;
import org.reactivecouchbase.json.JsObject;
import org.reactivecouchbase.json.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletionStage;

import static org.reactivecouchbase.json.Syntax.$;

@RestController
@RequestMapping("/api/izanami")
public class IzanamiProxyController {

    private final Proxy proxy;

    @Autowired
    public IzanamiProxyController(Proxy proxy) {
        this.proxy = proxy;
    }


    @GetMapping()
    public CompletionStage<ResponseEntity<String>> proxy(
            @CookieValue(value = "clientId", required = false) String clientId) {

        Option<JsObject> context = Option.of(clientId).map(id -> Json.obj($("clientId", id)));

        return proxy.statusAndStringResponse(context, Option.of(clientId))
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();
    }

    @PostMapping()
    public CompletionStage<ResponseEntity<String>> proxyWithContext(
            @CookieValue(value = "clientId", required = false) String clientId,
            @RequestBody String context
    ) {
        return proxy.statusAndStringResponse(Option.some(Json.parse(context).asObject()), Option.of(clientId))
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();
    }

    @PostMapping("/experiments/won")
    public CompletionStage<ResponseEntity<String>> markWon(
            @RequestParam(value = "experiment") String id,
            @CookieValue(value = "clientId", required = false) String clientId) {

        return proxy.markVariantWonStringResponse(id, clientId)
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();

    }

    @PostMapping("/experiments/displayed")
    public CompletionStage<ResponseEntity<String>> markDisplayed(
            @RequestParam(value = "experiment") String id,
            @CookieValue(value = "clientId", required = false) String clientId) {

        return proxy.markVariantDisplayedStringResponse(id, clientId)
                .map(resp ->
                        new ResponseEntity<>(resp._2, HttpStatus.valueOf(resp._1))
                ).toCompletableFuture();


    }
}
