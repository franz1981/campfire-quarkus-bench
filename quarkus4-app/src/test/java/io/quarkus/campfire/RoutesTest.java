package io.quarkus.campfire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import campfire.routes.Recognition;
import campfire.routes.RouteTable;
import campfire.routes.RouteTableKt;
import campfire.ruby.RubyIntegerKt;

/** The canonical {@code /rooms/<digits>} read directly gives the room id the route table gives. */
class RoutesTest {
    @Test
    void sameRoomIdAsTheRouteTable() {
        Random random = new Random(3);
        String[] odd = {"/rooms/", "/rooms", "/rooms/12/", "/rooms/12.json", "/rooms/abc", "/rooms/0012", "/rooms/+12", "/rooms/ 12", "/rooms/12/messages",
                "/rooms/1234567890123456789", "/rooms/99999999999999999999", "/ROOMS/12", "//rooms/12", "/rooms//12", "/rooms/0", "/up", "/"};
        for (String path : odd) assertEquals(reference(path), HttpRoutes.roomId(path), path);
        for (int i = 0; i < 20000; i++) {
            StringBuilder path = new StringBuilder("/rooms/");
            for (int d = 0, n = 1 + random.nextInt(18); d < n; d++) path.append((char) ('0' + random.nextInt(10)));
            assertEquals(reference(path.toString()), HttpRoutes.roomId(path.toString()), path.toString());
        }
    }

    private static long reference(String path) {
        if (!(RouteTable.Companion.getCampfire().recognize("GET", RouteTableKt.normalizePath(path)) instanceof Recognition.Matched matched)
                || !matched.getEndpoint().equals("rooms#show")) return -1;
        String id = matched.getParams().get("id");
        Long cast = id == null ? null : RubyIntegerKt.integerCast(id);
        return cast == null ? -1 : cast;
    }
}
