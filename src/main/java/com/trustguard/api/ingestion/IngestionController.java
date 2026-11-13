package com.trustguard.api.ingestion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trustguard.shared.error.TenantContextMissingException;
import com.trustguard.signals.service.IngestionCoordinator;
import com.trustguard.signals.service.IngestionResult;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The trackLogin endpoint (Rules 3.3, 6.4 and 16.4, Ruling 110). It answers 202 as soon as the event and its
 * outbox row are stored, or when the idempotency key was already claimed. It declares no "consumes": the request
 * limit filter owns the 415 (Rule 5.7 order).
 *
 * <p>The tenant comes only from the holder that the API key filter filled (Rule 4.3). The body is read once from the
 * cached request and passed on as bytes; the controller never parses, logs or echoes it. Errors are mapped by
 * {@link IngestionExceptionHandler}.
 */
@RestController
public class IngestionController {
    private static final String LOGIN_PATH = "/api/v1/signals/login";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_DUPLICATE = "DUPLICATE";
    private static final String MESSAGE_DUPLICATE = "Event already processed";
    private static final String MESSAGE_NO_TENANT_CONTEXT = "No tenant context is available for this request.";
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final IngestionCoordinator coordinator;
    //creates the controller
    public IngestionController(IngestionCoordinator coordinator){
        this.coordinator= coordinator;
    }
    //body of the stored event's 202 response
    public record AcceptedBody(String status, String eventId){}
    //the body of the duplicate's 202 response
    public record DuplicateBody(String status, String originalEventId, String message){}

    //accepts on login event
    @PostMapping(LOGIN_PATH)
    public ResponseEntity<Object> trackLogin(HttpServletRequest request) throws IOException
    {
        TenantContext tenant= TenantContextHolder.get();
        if(tenant== null){
            throw new TenantContextMissingException(MESSAGE_NO_TENANT_CONTEXT);
        }
        byte[] body= request.getInputStream().readAllBytes();
        IngestionResult result = coordinator.acceptLogin(body, tenant);
        Object payload= result instanceof IngestionResult.Duplicate duplicate ? new DuplicateBody(STATUS_DUPLICATE,
                duplicate.originalEventId().value(), MESSAGE_DUPLICATE) : new AcceptedBody(STATUS_ACCEPTED,
                ((IngestionResult.Accepted) result).eventId().value());
        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(JSON_UTF8).body(payload);
    }
}
