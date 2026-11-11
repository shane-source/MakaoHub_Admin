package controllers;

import play.filters.csrf.CSRF;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import java.nio.charset.StandardCharsets;
import java.util.Map;

abstract class StaffWebSupport extends Controller {
    static String csrf(Http.Request request) {
        return CSRF.getToken(request).map(CSRF.Token::value).orElse("");
    }

    static String field(Http.Request request, String name) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null) return "";
        String[] values = form.get(name);
        return values == null || values.length == 0 || values[0] == null ? "" : values[0].strip();
    }

    static boolean checked(Http.Request request, String name) {
        String value = field(request, name);
        return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    static boolean validEmail(String email) {
        return email != null && email.length() <= 320 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    static boolean validPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) return false;
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    static String flash(Http.Request request, String key) {
        return request.flash().get(key).orElse("");
    }

    static Result noStore(Result result) {
        return result.withHeaders(
                "Cache-Control", "no-store, private",
                "Pragma", "no-cache",
                "X-Content-Type-Options", "nosniff"
        );
    }
}

