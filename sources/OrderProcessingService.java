package com.retailcore.order.service;

import com.retailcore.delivery.DeliveryGateway;
import com.retailcore.payment.PaymentGateway;
import com.retailcore.promo.PromoService;
import com.retailcore.repository.OrderRepository;
import com.retailcore.repository.RouteRuleRepository;
import com.retailcore.status.OrderStatusService;
import com.retailcore.util.LegacySqlExecutor;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Пример фрагмента из монолитного ядра.
 * Намеренно показывает смешение ответственности:
 * - бизнес-правила заказа
 * - расчёт промо
 * - маршрутизация доставки
 * - работа со статусами
 * - прямые SQL-вызовы к Oracle
 * - синхронные обращения во внешние системы
 */
public class OrderProcessingService {

    private final OrderRepository orderRepository;
    private final PromoService promoService;
    private final PaymentGateway paymentGateway;
    private final DeliveryGateway deliveryGateway;
    private final RouteRuleRepository routeRuleRepository;
    private final OrderStatusService orderStatusService;
    private final LegacySqlExecutor legacySqlExecutor;
    private final DataSource dataSource;

    public OrderProcessingService(OrderRepository orderRepository,
                                  PromoService promoService,
                                  PaymentGateway paymentGateway,
                                  DeliveryGateway deliveryGateway,
                                  RouteRuleRepository routeRuleRepository,
                                  OrderStatusService orderStatusService,
                                  LegacySqlExecutor legacySqlExecutor,
                                  DataSource dataSource) {
        this.orderRepository = orderRepository;
        this.promoService = promoService;
        this.paymentGateway = paymentGateway;
        this.deliveryGateway = deliveryGateway;
        this.routeRuleRepository = routeRuleRepository;
        this.orderStatusService = orderStatusService;
        this.legacySqlExecutor = legacySqlExecutor;
        this.dataSource = dataSource;
    }

    public OrderResult createOrder(CreateOrderCommand cmd) {
        Order order = new Order();
        order.setCustomerId(cmd.customerId());
        order.setRegionCode(cmd.regionCode());
        order.setChannel(cmd.channel());
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus("NEW");

        // 1. Базовый расчёт цены в коде монолита
        BigDecimal total = cmd.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2. Историческое ветвление по каналам и регионам
        if ("MARKETPLACE".equals(cmd.channel())) {
            total = total.add(BigDecimal.valueOf(49)); // сервисный сбор marketplace
        }
        if ("FAR_EAST".equals(cmd.regionCode())) {
            total = total.add(BigDecimal.valueOf(350)); // региональная надбавка
        }

        // 3. Внешний promo-сервис используется не во всех сценариях
        if (!cmd.disablePromos()) {
            Optional<BigDecimal> discount = promoService.calculateDiscount(cmd.customerId(), cmd.items(), cmd.promocode());
            if (discount.isPresent()) {
                total = total.subtract(discount.get());
            } else {
                // fallback на старую логику в Oracle package
                BigDecimal legacyDiscount = legacySqlExecutor.queryForDecimal(
                        "select legacy_promo_pkg.calculate_discount(?, ?, ?) from dual",
                        cmd.customerId(), cmd.regionCode(), cmd.promocode());
                total = total.subtract(legacyDiscount);
            }
        }

        order.setTotalAmount(total);
        orderRepository.save(order);

        // 4. Прямая запись в служебную staging-таблицу для .NET интеграции
        legacySqlExecutor.execute(
                "insert into INT_ORDER_EXPORT(order_id, export_state, created_at) values (?, 'NEW', systimestamp)",
                order.getId());

        // 5. Выбор маршрута доставки по конфигу + Oracle
        RouteRule routeRule = routeRuleRepository.findRule(cmd.regionCode(), cmd.deliveryType(), cmd.channel())
                .orElseGet(() -> RouteRule.defaultRule());

        if (routeRule.requiresManualReview()) {
            orderStatusService.setStatus(order.getId(), "WAITING_MANUAL_REVIEW");
            return OrderResult.manualReview(order.getId(), total);
        }

        // 6. Синхронный вызов логистики в рамках оформления заказа
        DeliveryReservation reservation = deliveryGateway.reserveSlot(
                order.getId(),
                cmd.regionCode(),
                cmd.deliveryType(),
                routeRule.partnerCode());

        if (!reservation.success()) {
            orderStatusService.setStatus(order.getId(), "DELIVERY_PENDING");
            // Заказ остаётся жить, дальнейшая обработка пойдёт batch-процессом
            legacySqlExecutor.execute(
                    "insert into RETRY_DELIVERY_QUEUE(order_id, retry_after) values (?, systimestamp + interval '10' minute)",
                    order.getId());
            return OrderResult.acceptedWithDeferredDelivery(order.getId(), total);
        }

        // 7. Платёжный вызов также синхронный
        PaymentSession paymentSession = paymentGateway.createSession(order.getId(), total, cmd.paymentType());
        if (!paymentSession.created()) {
            orderStatusService.setStatus(order.getId(), "PAYMENT_PENDING");
            throw new IllegalStateException("Payment session was not created, orderId=" + order.getId());
        }

        // 8. Финальное обновление нескольких аспектов состояния
        orderStatusService.setStatus(order.getId(), "CONFIRMED");
        legacySqlExecutor.execute(
                "update ORDERS set payment_session_id = ?, delivery_partner = ?, updated_at = systimestamp where id = ?",
                paymentSession.sessionId(), routeRule.partnerCode(), order.getId());

        return OrderResult.confirmed(order.getId(), total, paymentSession.redirectUrl());
    }
}
