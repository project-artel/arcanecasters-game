package com.wordonline.server.playground;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import com.wordonline.server.auth.domain.PrincipalDetails;
import com.wordonline.server.session.service.SessionService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PlaygroundSubscriptionGuard implements ChannelInterceptor {
    private final ObjectProvider<SessionService> sessions;

    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || (headers.getCommand() != StompCommand.SUBSCRIBE
                && headers.getCommand() != StompCommand.SEND)) return message;
        String destination = headers.getDestination();
        if (destination == null) return message;
        if (destination.startsWith("/game/") && (destination.contains("*") || destination.contains("?")))
            throw new AccessDeniedException("Wildcard game subscriptions are not supported.");
        if (destination.startsWith("/game/artel-qa-")) {
            String[] parts = destination.split("/");
            if (headers.getCommand() != StompCommand.SUBSCRIBE || parts.length != 5
                    || !parts[3].equals("frameInfos")
                    || !(headers.getUser() instanceof PrincipalDetails principal) || principal.memberId == null
                    || !parts[4].equals(Long.toString(principal.memberId))) {
                throw new AccessDeniedException("Invalid QA match subscription.");
            }
            var session = sessions.getObject().getSessionObject(parts[2]);
            if (session == null || !session.isQaMatch() || session.getUserSide(principal.memberId) == null) {
                throw new AccessDeniedException("QA match subscription denied.");
            }
            return message;
        }
        if (!destination.contains("playground-")) return message;
        // No wildcard subscriptions or normal game inputs can reach a playground.
        String[] parts = destination.split("/");
        if (headers.getCommand() != StompCommand.SUBSCRIBE || parts.length != 5
                || !parts[1].equals("game") || !parts[3].equals("frameInfos")
                || !(headers.getUser() instanceof PrincipalDetails principal) || principal.memberId == null)
            throw new AccessDeniedException("Invalid playground subscription.");
        var session = sessions.getObject().getSessionObject(parts[2]);
        if (session == null || !(session.getGameLoop() instanceof PlaygroundLoop loop)
                || !loop.acceptsCommands() || session.getLeftUserId() != principal.memberId
                || !parts[4].equals(Long.toString(principal.memberId))
                || principal.getAuthorities().stream().noneMatch(authority ->
                    authority.getAuthority().equals("SUPER_ADMIN") || authority.getAuthority().equals("WORDONLINE_ADMIN")))
            throw new AccessDeniedException("Playground subscription denied.");
        return message;
    }
}
