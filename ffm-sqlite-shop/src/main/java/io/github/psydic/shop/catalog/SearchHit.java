package io.github.psydic.shop.catalog;

import java.math.BigDecimal;

/** A full-text search result: the product, a highlighted snippet and its relevance (lower bm25 = better). */
public record SearchHit(long id, String name, BigDecimal price, String snippet, double rank) {
}
