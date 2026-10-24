package io.github.psydic.shop;

/** A business error with the HTTP status the API answers with. */
public class ShopException extends RuntimeException {
    private final int status;

    public ShopException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ShopException notFound(String what, Object id) {
        return new ShopException(404, what + " " + id + " not found");
    }

    public static ShopException conflict(String message) {
        return new ShopException(409, message);
    }

    public static ShopException badRequest(String message) {
        return new ShopException(400, message);
    }
}
