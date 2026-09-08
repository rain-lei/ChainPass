package com.chainpass.vc.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chainpass.did.service.DIDService;
import com.chainpass.util.RedisCache;
import com.chainpass.vc.dto.VCDto;
import com.chainpass.vc.entity.VCRecord;
import com.chainpass.vc.entity.VCType;
import com.chainpass.vc.mapper.VCRecordMapper;
import com.chainpass.vc.mapper.VCTypeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 Ed25519 密钥验证签发、存储和验签之间的完整性约束。 */
class VCIntegrityTest {
    private VCService service;
    private VCRecord record;
    private VCRecordMapper records;
    private DIDService didService;
    private IssuerKeyService issuer;

    @BeforeEach
    void setUp() throws Exception {
        records = mock(VCRecordMapper.class);
        VCTypeMapper types = mock(VCTypeMapper.class);
        didService = mock(DIDService.class);
        issuer = spy(new IssuerKeyService(mock(RedisCache.class)));
        ReflectionTestUtils.setField(issuer, "issuerKeyPair",
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        doReturn(true).when(issuer).isKeyValid();
        when(didService.isValidDID(anyString())).thenReturn(true);

        VCType type = new VCType();
        type.setTypeCode("KYCCredential");
        type.setTypeName("KYC认证凭证");
        type.setValidityDays(730);
        type.setStatus(0);
        when(types.findByTypeCode("KYCCredential")).thenReturn(type);
        when(records.insert(any(VCRecord.class))).thenAnswer(invocation -> {
            record = invocation.getArgument(0);
            return 1;
        });

        service = new VCService(records, types, didService, issuer);
        VCDto.IssueVCRequest request = new VCDto.IssueVCRequest();
        request.setHolderDid("did:chainpass:holder");
        request.setVcType("KYCCredential");
        request.setClaims(Map.of("assuranceLevel", 1, "policy",
            Map.of("name", "人工审核", "checks", List.of("identity", "expiry"))));
        service.issueCredential(request);
        when(records.findByVcId(record.getVcId())).thenReturn(record);
    }

    @Test
    void issuedCredentialVerifiesAfterStorageRoundTrip() {
        // MySQL TIMESTAMP 默认不保存纳秒；验证不应依赖索引时间与正文逐字相等。
        record.setIssuedAt(Instant.ofEpochSecond(record.getIssuedAt().getEpochSecond()));
        record.setExpiresAt(Instant.ofEpochSecond(record.getExpiresAt().getEpochSecond()));
        assertTrue(service.verifyCredential(record.getVcId()).isValid());
    }

    @Test
    void changedClaimsCannotReuseOriginalSignature() {
        JSONObject body = JSON.parseObject(record.getVcData());
        body.getJSONObject("credentialSubject").getJSONObject("claims").put("assuranceLevel", 3);
        record.setVcData(body.toJSONString());
        assertFalse(service.verifyCredential(record.getVcId()).isValid());
    }

    @Test
    void unknownFieldsAreIncludedInIntegrityCheck() {
        JSONObject body = JSON.parseObject(record.getVcData());
        body.put("extraPrivilege", "admin");
        record.setVcData(body.toJSONString());
        assertFalse(service.verifyCredential(record.getVcId()).isValid());
    }

    @Test
    void changedBodyAndHashStillRequireAuthenticSignature() throws Exception {
        JSONObject body = JSON.parseObject(record.getVcData());
        body.getJSONObject("credentialSubject").getJSONObject("claims").put("assuranceLevel", 3);
        record.setVcData(body.toJSONString());
        record.setCredentialHash(bodyHash(body));
        assertFalse(service.verifyCredential(record.getVcId()).isValid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"holder", "type", "issuer", "id"})
    void databaseMetadataMustMatchSignedBody(String field) {
        String originalId = record.getVcId();
        switch (field) {
            case "holder" -> record.setHolderDid("did:chainpass:another");
            case "type" -> record.setVcType("AdminCredential");
            case "issuer" -> record.setIssuerDid("did:chainpass:another-issuer");
            case "id" -> record.setVcId("urn:uuid:another");
        }
        assertFalse(service.verifyCredential(originalId).isValid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "proofValue", "type", "proofPurpose", "verificationMethod"})
    void damagedProofIsRejected(String field) {
        JSONObject body = JSON.parseObject(record.getVcData());
        if ("missing".equals(field)) {
            body.remove("proof");
        } else {
            body.getJSONObject("proof").put(field, "altered");
        }
        record.setVcData(body.toJSONString());
        assertFalse(service.verifyCredential(record.getVcId()).isValid());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"null", "{invalid", "[]", "{}"})
    void malformedContentReturnsInvalidInsteadOfThrowing(String content) {
        record.setVcData(content);
        assertFalse(service.verifyCredential(record.getVcId()).isValid());
    }

    @Test
    void databaseExpiryCannotExtendSignedExpiry() throws Exception {
        JSONObject body = JSON.parseObject(record.getVcData());
        body.put("expirationDate", Instant.now().minusSeconds(60).toString());
        record.setCredentialHash(bodyHash(body));
        record.setSignature(issuer.sign(record.getCredentialHash()));
        body.getJSONObject("proof").put("proofValue", record.getSignature());
        record.setVcData(body.toJSONString());
        assertEquals("凭证已过期", service.verifyCredential(record.getVcId()).getMessage());
    }

    @Test
    void revokedHolderStillInvalidatesIntactCredential() {
        when(didService.isValidDID(record.getHolderDid())).thenReturn(false);
        assertFalse(service.verifyCredential(record.getVcId()).isValid());
    }

    @Test
    void paymentGateRejectsTamperedCredential() {
        when(records.findValidByHolderDidAndType(record.getHolderDid(), record.getVcType()))
            .thenReturn(List.of(record));
        record.setVcData("{}");
        assertFalse(service.hasValidCredential(record.getHolderDid(), record.getVcType()));
    }

    private String bodyHash(JSONObject credential) throws Exception {
        JSONObject unsigned = JSON.parseObject(credential.toJSONString());
        unsigned.remove("proof");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(unsigned.toJSONString().getBytes(StandardCharsets.UTF_8)));
    }
}
