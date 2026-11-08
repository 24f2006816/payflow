package com.payflow.payment;

import com.payflow.account.AccountEntity;
import com.payflow.account.AccountRepository;
import com.payflow.common.ApiException;
import com.payflow.common.NotFoundException;
import com.payflow.domain.DomainException;
import com.payflow.domain.Money;
import com.payflow.domain.payment.FeeCalculator;
import com.payflow.domain.payment.PaymentRules;
import com.payflow.domain.payment.PaymentStatus;
import com.payflow.domain.payment.PostingPlans;
import com.payflow.ledger.LedgerService;
import com.payflow.ledger.SystemAccounts;
import com.payflow.merchant.MerchantService;
import com.payflow.merchant.MerchantView;
import com.payflow.outbox.OutboxWriter;
import com.payflow.security.CurrentClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payment use cases. Each public method is ONE database transaction that:
 * locks the rows it changes -> applies pure domain rules -> posts the ledger entry ->
 * updates the payment -> appends an outbox event. Either all of it commits or none of it does.
 */
@Service
public class PaymentService {

    public record CreatePayment(UUID merchantId, UUID customerAccountId, long amountMinor, String currency,
                                String description) {
    }

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AccountRepository accounts;
    private final MerchantService merchants;
    private final LedgerService ledger;
    private final SystemAccounts systemAccounts;
    private final FeeCalculator feeCalculator;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    public PaymentService(PaymentRepository payments, RefundRepository refunds, AccountRepository accounts,
                          MerchantService merchants, LedgerService ledger, SystemAccounts systemAccounts,
                          FeeCalculator feeCalculator, OutboxWriter outbox, MeterRegistry meters) {
        this.payments = payments;
        this.refunds = refunds;
        this.accounts = accounts;
        this.merchants = merchants;
        this.ledger = ledger;
        this.systemAccounts = systemAccounts;
        this.feeCalculator = feeCalculator;
        this.outbox = outbox;
        this.meters = meters;
    }

    /**
     * Authorizes (reserves) funds from a customer wallet for a merchant.
     * Insufficient funds is a business outcome, not an error: we persist a FAILED payment and return it.
     */
    @Transactional
    public PaymentEntity authorize(CurrentClient client, String idempotencyKey, CreatePayment cmd) {
        var existing = payments.findByClientIdAndIdempotencyKey(client.clientId(), idempotencyKey);
        if (existing.isPresent()) {
            return existing.get(); // domain-level idempotency (second line of defence)
        }
        Money amount = Money.positive(cmd.amountMinor(), cmd.currency());
        MerchantView merchant = merchants.get(cmd.merchantId());
        if (!merchant.isActive()) {
            throw ApiException.unprocessable("merchant_inactive", "merchant " + merchant.id() + " is not active");
        }
        AccountEntity customer = accounts.findById(cmd.customerAccountId())
                .filter(a -> a.getAccountType() == AccountEntity.AccountType.CUSTOMER_WALLET)
                .orElseThrow(() -> new NotFoundException("customer wallet", cmd.customerAccountId()));
        if (!customer.getCurrency().equals(amount.currency())) {
            throw new DomainException.CurrencyMismatch("wallet is " + customer.getCurrency()
                    + " but payment is " + amount.currency());
        }
        AccountEntity merchantAccount = accounts.findByOwnerTypeAndOwnerIdAndAccountTypeAndCurrency(
                        AccountEntity.OwnerType.MERCHANT, merchant.id(), AccountEntity.AccountType.MERCHANT_BALANCE,
                        amount.currency())
                .orElseThrow(() -> ApiException.unprocessable("merchant_currency_unsupported",
                        "merchant does not accept " + amount.currency()));

        PaymentEntity payment = PaymentEntity.newPayment(UUID.randomUUID(), client.clientId(), idempotencyKey,
                merchant.id(), customer.getId(), merchantAccount.getId(), amount.minor(), amount.currency(),
                cmd.description());
        try {
            ledger.post(PostingPlans.authorize(payment.getId(), customer.getId(),
                    systemAccounts.holds(amount.currency()), amount.minor(), amount.currency()));
        } catch (DomainException.InsufficientFunds e) {
            payment.markFailed("INSUFFICIENT_FUNDS");
        }
        payment = payments.save(payment);
        outbox.append("payment", payment.getId(),
                payment.getStatus() == PaymentStatus.FAILED ? "payment.failed" : "payment.authorized",
                payment.getMerchantId(), eventData(payment));
        meters.counter("payflow.payments", "operation", "authorize", "outcome", payment.getStatus().name()).increment();
        return payment;
    }

    @Transactional
    public PaymentEntity capture(CurrentClient client, UUID paymentId, Long requestedAmount) {
        PaymentEntity payment = lockForWrite(client, paymentId);
        long captured = PaymentRules.resolveCaptureAmount(payment.getStatus(), payment.getAmountMinor(), requestedAmount);
        long fee = feeCalculator.feeFor(captured);
        String ccy = payment.getCurrency();
        ledger.post(PostingPlans.capture(payment.getId(), systemAccounts.holds(ccy), payment.getMerchantAccountId(),
                systemAccounts.fees(ccy), payment.getCustomerAccountId(), payment.getAmountMinor(), captured, fee, ccy));
        payment.markCaptured(captured, fee);
        outbox.append("payment", payment.getId(), "payment.captured", payment.getMerchantId(), eventData(payment));
        meters.counter("payflow.payments", "operation", "capture", "outcome", "CAPTURED").increment();
        return payment;
    }

    @Transactional
    public PaymentEntity cancel(CurrentClient client, UUID paymentId) {
        PaymentEntity payment = lockForWrite(client, paymentId);
        PaymentRules.validateCancel(payment.getStatus());
        String ccy = payment.getCurrency();
        ledger.post(PostingPlans.cancel(payment.getId(), systemAccounts.holds(ccy), payment.getCustomerAccountId(),
                payment.getAmountMinor(), ccy));
        payment.markCanceled();
        outbox.append("payment", payment.getId(), "payment.canceled", payment.getMerchantId(), eventData(payment));
        meters.counter("payflow.payments", "operation", "cancel", "outcome", "CANCELED").increment();
        return payment;
    }

    /**
     * Refunds part or all of a captured payment. Fails with 422 if the merchant balance cannot cover it
     * (a real platform would then debit a reserve account; out of scope here).
     */
    @Transactional
    public PaymentEntity refund(CurrentClient client, UUID paymentId, String idempotencyKey, long amountMinor,
                                String reason) {
        PaymentEntity payment = lockForWrite(client, paymentId);
        if (refunds.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey).isPresent()) {
            return payment; // this refund was already applied
        }
        PaymentStatus next = PaymentRules.statusAfterRefund(payment.getStatus(), payment.getCapturedMinor(),
                payment.getRefundedMinor(), amountMinor);
        RefundEntity refund = refunds.save(new RefundEntity(UUID.randomUUID(), paymentId, idempotencyKey,
                amountMinor, reason));
        ledger.post(PostingPlans.refund(refund.getId(), payment.getMerchantAccountId(), payment.getCustomerAccountId(),
                amountMinor, payment.getCurrency()));
        payment.applyRefund(amountMinor, next);
        Map<String, Object> data = eventData(payment);
        data.put("refundId", refund.getId());
        data.put("refundAmountMinor", amountMinor);
        outbox.append("payment", payment.getId(), "payment.refunded", payment.getMerchantId(), data);
        meters.counter("payflow.payments", "operation", "refund", "outcome", next.name()).increment();
        return payment;
    }

    @Transactional(readOnly = true)
    public PaymentEntity get(CurrentClient client, UUID paymentId) {
        PaymentEntity payment = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("payment", paymentId));
        client.requireAccessToMerchant(payment.getMerchantId(), "payment", paymentId);
        return payment;
    }

    @Transactional(readOnly = true)
    public Page<PaymentEntity> listForMerchant(CurrentClient client, UUID merchantId, int page, int size) {
        client.requireAccessToMerchant(merchantId, "merchant", merchantId);
        return payments.findByMerchantId(merchantId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    private PaymentEntity lockForWrite(CurrentClient client, UUID paymentId) {
        PaymentEntity payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException("payment", paymentId));
        client.requireAccessToMerchant(payment.getMerchantId(), "payment", paymentId);
        return payment;
    }

    private static Map<String, Object> eventData(PaymentEntity p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", p.getId());
        data.put("status", p.getStatus().name());
        data.put("amountMinor", p.getAmountMinor());
        data.put("capturedMinor", p.getCapturedMinor());
        data.put("refundedMinor", p.getRefundedMinor());
        data.put("feeMinor", p.getFeeMinor());
        data.put("currency", p.getCurrency());
        data.put("customerAccountId", p.getCustomerAccountId());
        if (p.getFailureReason() != null) {
            data.put("failureReason", p.getFailureReason());
        }
        return data;
    }
}
