package com.chainpass.payment.service;

import com.chainpass.did.entity.DIDDocument;
import com.chainpass.did.service.DIDService;
import com.chainpass.exception.BusinessException;
import com.chainpass.payment.dto.PaymentDto;
import com.chainpass.payment.entity.PaymentOrder;
import com.chainpass.payment.entity.Transaction;
import com.chainpass.payment.entity.Wallet;
import com.chainpass.payment.mapper.*;
import com.chainpass.compliance.kyc.KYCService;
import com.chainpass.vc.service.VCService;
import com.chainpass.util.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 支付服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private WalletMapper walletMapper;

    @Mock
    private PaymentOrderMapper orderMapper;

    @Mock
    private TransactionMapper transactionMapper;

    @Mock
    private ExchangeRateMapper rateMapper;

    @Mock
    private DIDService didService;

    @Mock
    private KYCService kycService;

    @Mock
    private VCService vcService;

    @Mock
    private RedisCache redisCache;

    @InjectMocks
    private PaymentService paymentService;

    private String payerDid;
    private String payeeDid;
    private Wallet payerWallet;
    private Wallet payeeWallet;

    @BeforeEach
    void setUp() {
        payerDid = "did:chainpass:payer";
        payeeDid = "did:chainpass:payee";

        payerWallet = new Wallet();
        payerWallet.setId(1L);
        payerWallet.setDid(payerDid);
        payerWallet.setAddress("0xpayer");
        payerWallet.setBalanceCny(new BigDecimal("10000.00"));
        payerWallet.setBalanceUsd(new BigDecimal("1500.00"));
        payerWallet.setBalanceEth(new BigDecimal("0.5"));
        payerWallet.setStatus(0);

        payeeWallet = new Wallet();
        payeeWallet.setId(2L);
        payeeWallet.setDid(payeeDid);
        payeeWallet.setAddress("0xpayee");
        payeeWallet.setBalanceCny(BigDecimal.ZERO);
        payeeWallet.setStatus(0);
    }

    @Test
    @DisplayName("创建支付订单 - 成功")
    void testCreatePayment_Success() {
        // Given
        PaymentDto.CreatePaymentRequest request = new PaymentDto.CreatePaymentRequest();
        request.setPayeeDid(payeeDid);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("CNY");
        request.setSourceCountry("CN");
        request.setTargetCountry("SG");
        request.setBeneficiaryName("Test Payee");
        request.setPaymentPurpose("EDUCATION");

        when(didService.isValidDID(payerDid)).thenReturn(true);
        when(didService.isValidDID(payeeDid)).thenReturn(true);
        when(walletMapper.findByDid(payerDid)).thenReturn(payerWallet);
        when(walletMapper.findByDid(payeeDid)).thenReturn(payeeWallet);
        when(kycService.isDIDKYCVerified(payerDid)).thenReturn(true);
        when(kycService.isDIDKYCVerified(payeeDid)).thenReturn(true);
        when(kycService.isBeneficiaryNameConsistent(payeeDid, "Test Payee")).thenReturn(true);
        when(vcService.hasValidCredential(payerDid, "KYCCredential")).thenReturn(true);
        when(vcService.hasValidCredential(payeeDid, "KYCCredential")).thenReturn(true);
        when(orderMapper.insert(any(PaymentOrder.class))).thenReturn(1);

        // When
        PaymentOrder order = paymentService.createPayment(payerDid, request);

        // Then
        assertNotNull(order);
        assertNotNull(order.getOrderNo());
        assertTrue(order.getOrderNo().startsWith("PAY"));
        assertEquals(payerDid, order.getPayerDid());
        assertEquals(payeeDid, order.getPayeeDid());
        assertEquals(0, order.getStatus());

        verify(orderMapper, times(1)).insert(any(PaymentOrder.class));
    }

    @Test
    @DisplayName("跨币种订单按源币收取手续费并按目标币到账")
    void testCreatePayment_CrossCurrencyAccounting() {
        PaymentDto.CreatePaymentRequest request = new PaymentDto.CreatePaymentRequest();
        request.setPayeeDid(payeeDid);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("CNY");
        request.setTargetCurrency("USD");
        request.setSourceCountry("CN");
        request.setTargetCountry("SG");
        request.setBeneficiaryName("Test Payee");
        request.setPaymentPurpose("EDUCATION");

        when(didService.isValidDID(payerDid)).thenReturn(true);
        when(didService.isValidDID(payeeDid)).thenReturn(true);
        when(walletMapper.findByDid(payerDid)).thenReturn(payerWallet);
        when(walletMapper.findByDid(payeeDid)).thenReturn(payeeWallet);
        when(rateMapper.getRate("CNY", "USD")).thenReturn(new BigDecimal("0.1389"));
        when(kycService.isDIDKYCVerified(payerDid)).thenReturn(true);
        when(kycService.isDIDKYCVerified(payeeDid)).thenReturn(true);
        when(kycService.isBeneficiaryNameConsistent(payeeDid, "Test Payee")).thenReturn(true);
        when(vcService.hasValidCredential(payerDid, "KYCCredential")).thenReturn(true);
        when(vcService.hasValidCredential(payeeDid, "KYCCredential")).thenReturn(true);

        PaymentOrder order = paymentService.createPayment(payerDid, request);

        assertEquals(new BigDecimal("100.00"), order.getOriginalAmount());
        assertEquals("CNY", order.getOriginalCurrency());
        assertEquals(new BigDecimal("13.89"), order.getAmount());
        assertEquals("USD", order.getCurrency());
        assertEquals(new BigDecimal("0.10"), order.getFeeAmount());
        assertEquals("CNY", order.getFeeCurrency());
    }

    @Test
    @DisplayName("创建支付订单 - 付款人DID无效")
    void testCreatePayment_InvalidPayerDid() {
        // Given
        PaymentDto.CreatePaymentRequest request = new PaymentDto.CreatePaymentRequest();
        request.setPayeeDid(payeeDid);
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency("CNY");
        request.setSourceCountry("CN");
        request.setTargetCountry("SG");
        request.setBeneficiaryName("Test Payee");
        request.setPaymentPurpose("EDUCATION");

        when(didService.isValidDID(payerDid)).thenReturn(false);

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            paymentService.createPayment(payerDid, request);
        });

        verify(orderMapper, never()).insert(any(PaymentOrder.class));
    }

    @Test
    @DisplayName("执行支付 - 付款人DID吊销后不再扣款")
    void testExecutePayment_RejectsRevokedPayerDidBeforeClaimingOrder() {
        PaymentOrder order = executableOrder();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(order);
        when(didService.isValidDID(payerDid)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> paymentService.executePayment(order.getOrderNo(), payerDid));

        assertEquals("付款人DID无效或已吊销，请重新创建订单", exception.getMessage());
        verify(orderMapper, never()).markProcessing(any());
        verify(walletMapper, never()).addCnyBalanceWithVersion(any(), any(), any());
        verify(transactionMapper, never()).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("执行支付 - 凭证失效后不再扣款")
    void testExecutePayment_RejectsInvalidPayerCredentialBeforeClaimingOrder() {
        PaymentOrder order = executableOrder();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(order);
        when(didService.isValidDID(payerDid)).thenReturn(true);
        when(didService.isValidDID(payeeDid)).thenReturn(true);
        when(kycService.isDIDKYCVerified(payerDid)).thenReturn(true);
        when(vcService.hasValidCredential(payerDid, "KYCCredential")).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> paymentService.executePayment(order.getOrderNo(), payerDid));

        assertEquals("付款人缺少当前有效的审核结论凭证，请重新完成审核后创建订单", exception.getMessage());
        verify(orderMapper, never()).markProcessing(any());
        verify(walletMapper, never()).addCnyBalanceWithVersion(any(), any(), any());
    }

    @Test
    @DisplayName("执行支付 - 扣款前重新验证当前身份状态")
    void testExecutePayment_RevalidatesEligibilityBeforeLedgerMutation() {
        PaymentOrder order = executableOrder();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(order);
        when(didService.isValidDID(payerDid)).thenReturn(true);
        when(didService.isValidDID(payeeDid)).thenReturn(true);
        when(kycService.isDIDKYCVerified(payerDid)).thenReturn(true);
        when(vcService.hasValidCredential(payerDid, "KYCCredential")).thenReturn(true);
        when(orderMapper.markProcessing(order.getOrderNo())).thenReturn(1);
        when(walletMapper.selectById(1L)).thenReturn(payerWallet);
        when(walletMapper.selectById(2L)).thenReturn(payeeWallet);
        when(walletMapper.addCnyBalanceWithVersion(eq(1L), eq(new BigDecimal("-100.10")), any()))
            .thenReturn(1);
        when(walletMapper.addCnyBalanceWithVersion(eq(2L), eq(new BigDecimal("100.00")), any()))
            .thenReturn(1);
        when(transactionMapper.insert(any(Transaction.class))).thenReturn(1);
        when(orderMapper.markPaid(eq(order.getOrderNo()), any(Instant.class))).thenReturn(1);

        Transaction transaction = paymentService.executePayment(order.getOrderNo(), payerDid);

        assertNotNull(transaction);
        verify(didService).isValidDID(payerDid);
        verify(didService).isValidDID(payeeDid);
        verify(kycService).isDIDKYCVerified(payerDid);
        verify(vcService).hasValidCredential(payerDid, "KYCCredential");
        verify(orderMapper).markProcessing(order.getOrderNo());
        verify(walletMapper).addCnyBalanceWithVersion(eq(1L), eq(new BigDecimal("-100.10")), any());
        verify(walletMapper).addCnyBalanceWithVersion(eq(2L), eq(new BigDecimal("100.00")), any());
    }

    @Test
    @DisplayName("获取汇率 - 成功")
    void testGetExchangeRate_Success() {
        // Given
        when(rateMapper.getRate("CNY", "USD")).thenReturn(new BigDecimal("0.1389"));

        // When
        var rate = paymentService.getExchangeRate("CNY", "USD");

        // Then
        assertNotNull(rate);
        assertEquals(new BigDecimal("0.1389"), rate);
    }

    @Test
    @DisplayName("获取汇率 - 相同货币")
    void testGetExchangeRate_SameCurrency() {
        // When
        var rate = paymentService.getExchangeRate("CNY", "CNY");

        // Then
        assertEquals(BigDecimal.ONE, rate);
    }

    @Test
    @DisplayName("创建钱包 - 成功")
    void testCreateWallet_Success() {
        // Given
        Long userId = 1L;
        when(walletMapper.findByUserId(userId)).thenReturn(null);
        when(walletMapper.insert(any(Wallet.class))).thenReturn(1);

        // When
        Wallet wallet = paymentService.createWallet(userId, payerDid);

        // Then
        assertNotNull(wallet);
        assertTrue(wallet.getAddress().startsWith("0x"));
        assertEquals(payerDid, wallet.getDid());

        assertEquals(BigDecimal.ZERO, wallet.getBalanceCny());
        verify(walletMapper, never()).addCnyBalance(any(), any());
    }

    private PaymentOrder executableOrder() {
        payerWallet.setVersion(0);
        payeeWallet.setVersion(0);
        return PaymentOrder.builder()
            .orderNo("PAY-executable")
            .payerDid(payerDid)
            .payeeDid(payeeDid)
            .payerWalletId(payerWallet.getId())
            .payeeWalletId(payeeWallet.getId())
            .amount(new BigDecimal("100.00"))
            .currency("CNY")
            .originalAmount(new BigDecimal("100.00"))
            .originalCurrency("CNY")
            .feeAmount(new BigDecimal("0.10"))
            .status(0)
            .expiredAt(Instant.now().plusSeconds(300))
            .build();
    }
}
