package dev.beelive.com.order_service.serviceImpl;

import dev.beelive.com.order_service.dto.InventoryResponseDto;
import dev.beelive.com.order_service.dto.OrderLineItemsDto;
import dev.beelive.com.order_service.dto.OrderRequest;
import dev.beelive.com.order_service.event.OrderPlacedEvent;
import dev.beelive.com.order_service.model.Order;
import dev.beelive.com.order_service.model.OrderLineItems;
import dev.beelive.com.order_service.repository.OrderRepository;
import dev.beelive.com.order_service.service.OrderService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final ObservationRegistry observationRegistry;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    @SneakyThrows
    public String placeOrder(OrderRequest orderRequest) {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .toList();

        // Call Inventory Service, and place order if product is in
        // stock
        Observation inventoryServiceObservation = Observation.createNotStarted("inventory-service-lookup",
                this.observationRegistry);
        inventoryServiceObservation.lowCardinalityKeyValue("call", "inventory-service");

        return inventoryServiceObservation.observe(() -> {
            InventoryResponseDto[] inventoryResponseArray = webClientBuilder.build().get()
                    .uri("http://inventory-service/api/inventory",
                            uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                    .retrieve()
                    .bodyToMono(InventoryResponseDto[].class)
                    .block();

            assert inventoryResponseArray != null;
            boolean allProductsInStock = Arrays.stream(inventoryResponseArray)
                    .allMatch(InventoryResponseDto::isInStock);

            if (allProductsInStock) {
                orderRepository.save(order);
                // publish Order Placed Event
                applicationEventPublisher.publishEvent(new OrderPlacedEvent(this, order.getOrderNumber()));
                return "Order Placed";
            } else {
                throw new IllegalArgumentException("Product is not in stock, please try again later");
            }
        });
    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto){
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }
}
