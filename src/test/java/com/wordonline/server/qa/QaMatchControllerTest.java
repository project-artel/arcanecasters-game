package com.wordonline.server.qa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import com.wordonline.server.auth.domain.PrincipalDetails;
import com.wordonline.server.game.domain.GameSessionData;
import com.wordonline.server.game.domain.PlayerData;
import com.wordonline.server.game.domain.SessionObject;
import com.wordonline.server.game.dto.Master;
import com.wordonline.server.game.service.GameContext;
import com.wordonline.server.game.service.GameLoop;
import com.wordonline.server.game.service.ResultChecker;
import com.wordonline.server.server.entity.ServerState;
import com.wordonline.server.server.service.ServerStatusService;
import com.wordonline.server.server.service.ServerUrlProvider;
import com.wordonline.server.session.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class QaMatchControllerTest {
    private final SessionService sessions = mock(SessionService.class);
    private final ServerStatusService status = mock(ServerStatusService.class);
    private final QaMatchController controller = new QaMatchController(
            new QaMatchProperties(Set.of(1L, 2L), -3L), sessions, mock(ServerUrlProvider.class), status);

    @Test
    void rejectsNonQaAccountBeforeLookingUpSession() {
        var error = assertThrows(ResponseStatusException.class, () ->
                controller.command("artel-qa-1", new PrincipalDetails(3L, List.of()), null));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(sessions);
    }

    @Test
    void rejectsNormalMatchEvenForQaParticipant() {
        var session = mock(SessionObject.class);
        when(sessions.getSessionObject("normal")).thenReturn(session);
        var error = assertThrows(ResponseStatusException.class, () ->
                controller.command("normal", new PrincipalDetails(1L, List.of()), null));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(session, never()).getGameContext();
    }

    @Test
    void rejectsQaMatchForAllowlistedNonParticipant() {
        var session = mock(SessionObject.class);
        when(session.isQaMatch()).thenReturn(true);
        when(sessions.getSessionObject("artel-qa-1")).thenReturn(session);
        var error = assertThrows(ResponseStatusException.class, () ->
                controller.command("artel-qa-1", new PrincipalDetails(2L, List.of()), null));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(session, never()).getGameContext();
    }

    @Test
    void rejectsDuelWithNonQaPeer() {
        var error = assertThrows(ResponseStatusException.class, () ->
                controller.create(new QaMatchController.Create(1L, 3L)));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(sessions);
    }

    @Test
    void replacesHandOnlyWhenQueuedActionRuns() {
        var session = mock(SessionObject.class);
        var context = mock(GameContext.class);
        var data = mock(GameSessionData.class);
        var loop = mock(GameLoop.class);
        var player = new PlayerData(null);
        player.cards.add(7L);
        var actions = new ArrayList<Runnable>();
        when(sessions.getSessionObject("artel-qa-1")).thenReturn(session);
        when(session.isQaMatch()).thenReturn(true);
        when(session.getUserSide(1L)).thenReturn(Master.LeftPlayer);
        when(session.getUserId(Master.LeftPlayer)).thenReturn(1L);
        when(session.getOwnedMagicIds(1L)).thenReturn(Set.of(8L));
        when(session.getGameContext()).thenReturn(context);
        when(session.getGameLoop()).thenReturn(loop);
        when(loop.is_running()).thenReturn(true);
        when(context.getResultChecker()).thenReturn(mock(ResultChecker.class));
        when(context.getGameSessionData()).thenReturn(data);
        when(data.getPlayerData(Master.LeftPlayer)).thenReturn(player);
        when(context.submitAction(eq("artelQaCommand"), any())).thenAnswer(
                call -> actions.add(call.getArgument(1)));

        var result = controller.command("artel-qa-1", new PrincipalDetails(1L, List.of()),
                new QaMatchController.Command("set_hand", "Self", null, null,
                        List.of(8L, 8L), null, null, null));
        assertEquals(List.of(7L), player.cards);
        actions.getFirst().run();
        assertEquals(List.of(8L, 8L), player.cards);
        assertEquals("hand=[8, 8]", result.join().message());
    }
}
