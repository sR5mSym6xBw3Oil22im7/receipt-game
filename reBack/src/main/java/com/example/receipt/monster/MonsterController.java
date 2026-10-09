package com.example.receipt.monster;

import com.example.receipt.monster.MonsterViews.BattleView;
import com.example.receipt.monster.MonsterViews.FieldView;
import com.example.receipt.monster.MonsterViews.FlipResponse;
import com.example.receipt.monster.MonsterViews.ImageCardResponse;
import com.example.receipt.monster.MonsterViews.RoomView;
import com.example.receipt.monster.MonsterViews.SeatResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * モンスターレシート対戦のAPI（要件定義書 12.2）。ログイン不要で、席トークン（X-Seat-Token）で操作する。
 * 応答はすべて Cache-Control: no-store。
 */
@RestController
@RequestMapping("/api/monster/rooms")
public class MonsterController {
    static final String TOKEN_HEADER = "X-Seat-Token";

    private final MonsterRoomService service;
    private final MonsterRateLimiter limiter;

    public MonsterController(MonsterRoomService service, MonsterRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    public record FlipRequest(Integer index) {
    }

    public record SelectRequest(Long cardId) {
    }

    public record RematchRequest(Integer round) {
    }

    @PostMapping
    public ResponseEntity<SeatResponse> create(HttpServletRequest request) {
        limiter.check(MonsterRateLimiter.Action.CREATE_ROOM, request);
        return ok(service.create());
    }

    @PostMapping("/{code}/join")
    public ResponseEntity<SeatResponse> join(@PathVariable String code, HttpServletRequest request) {
        limiter.check(MonsterRateLimiter.Action.JOIN_ROOM, request);
        return ok(service.join(code));
    }

    @GetMapping("/{code}")
    public ResponseEntity<RoomView> state(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ok(service.state(code, token));
    }

    @GetMapping("/{code}/field")
    public ResponseEntity<FieldView> field(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ok(service.field(code, token));
    }

    @PostMapping("/{code}/shuffle")
    public ResponseEntity<FieldView> shuffle(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ok(service.shuffle(code, token));
    }

    @PostMapping(value = "/{code}/flip", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FlipResponse> flip(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                             @RequestBody FlipRequest body) {
        return ok(service.flip(code, token, body == null || body.index() == null ? -1 : body.index()));
    }

    @PostMapping(value = "/{code}/image-card", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageCardResponse> imageCard(@PathVariable String code,
                                                       @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                       @RequestParam("file") MultipartFile file,
                                                       HttpServletRequest request) throws IOException {
        // 席と状態を先に確かめてから回数を数える（無関係な要求で上限を消費させない）
        service.field(code, token);
        limiter.check(MonsterRateLimiter.Action.IMAGE_CARD, request);
        return ok(service.imageCard(code, token, file.getBytes()));
    }

    @PostMapping(value = "/{code}/select", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RoomView> select(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                           @RequestBody SelectRequest body) {
        return ok(service.select(code, token, body == null || body.cardId() == null ? -1 : body.cardId()));
    }

    @GetMapping("/{code}/battle")
    public ResponseEntity<BattleView> battle(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ok(service.battle(code, token));
    }

    @PostMapping(value = "/{code}/rematch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RoomView> rematch(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                            @RequestBody RematchRequest body) {
        return ok(service.rematch(code, token, body == null || body.round() == null ? -1 : body.round()));
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<Void> leave(@PathVariable String code, @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        service.leave(code, token);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
