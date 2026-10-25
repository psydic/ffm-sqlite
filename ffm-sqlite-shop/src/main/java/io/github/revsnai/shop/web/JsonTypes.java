package io.github.revsnai.shop.web;

import io.github.revsnai.shop.cart.Cart;
import io.github.revsnai.shop.catalog.Product;
import io.github.revsnai.shop.catalog.SearchHit;
import io.github.revsnai.shop.customer.Customer;
import io.github.revsnai.shop.order.Order;
import io.github.revsnai.shop.report.ReportRepository;
import io.micronaut.serde.annotation.SerdeImport;

/**
 * The domain records the API returns, made JSON-serializable here so that the domain and repository packages
 * stay free of framework annotations. Micronaut Serialization generates the (de)serializers at compile time.
 */
@SerdeImport(Product.class)
@SerdeImport(SearchHit.class)
@SerdeImport(Customer.class)
@SerdeImport(Cart.class)
@SerdeImport(Cart.Line.class)
@SerdeImport(Order.class)
@SerdeImport(Order.Line.class)
@SerdeImport(ReportRepository.TopProduct.class)
@SerdeImport(ReportRepository.DailySales.class)
final class JsonTypes {
    private JsonTypes() {
    }
}
