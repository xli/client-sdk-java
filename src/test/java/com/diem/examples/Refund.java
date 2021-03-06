// Copyright (c) The Diem Core Contributors
// SPDX-License-Identifier: Apache-2.0

package com.diem.examples;

import com.diem.*;
import com.diem.jsonrpc.StaleResponseException;
import com.diem.jsonrpc.JsonRpc;
import com.novi.serde.Bytes;
import com.novi.serde.DeserializationError;
import org.junit.Assert;
import org.junit.Test;
import com.diem.stdlib.Helpers;
import com.diem.types.Metadata;
import com.diem.utils.CurrencyCode;

import java.util.List;

public class Refund {
    @Test
    public void custodialAccountToCustodialAccountUnderThreshold() throws DiemException {
        DiemClient client = Testnet.createClient();
        LocalAccount senderParentVASPAccount = Utils.genAccount(client, 1_000_000);
        LocalAccount senderChildVASPAccount = Utils.genChildVASPAccount(client, senderParentVASPAccount, 500_000l);

        SubAddress senderCustodialUserSubAddress = SubAddress.generate();

        LocalAccount receiverParentVASPAccount = Utils.genAccount(client, 1_000_000);
        LocalAccount receiverChildVASPAccount = Utils.genChildVASPAccount(client, receiverParentVASPAccount, 500_000l);

        SubAddress receiverCustodialUserSubAddress = SubAddress.generate();

        // make a transfer
        long transactionVersion = Utils.submitAndWait(client, senderChildVASPAccount, Helpers.encode_peer_to_peer_with_metadata_script(
                Testnet.XUS_TYPE, receiverChildVASPAccount.address, 500_000L,
                TransactionMetadata.createGeneralMetadataWithFromToSubAddresses(
                        senderCustodialUserSubAddress, receiverCustodialUserSubAddress).getMetadata(),
                new Bytes(new byte[0]) // no metadata signature for GeneralMetadata
        ));

        Assert.assertEquals(500_000, Utils.getAccountBalance(client, senderParentVASPAccount));
        Assert.assertEquals(0, Utils.getAccountBalance(client, senderChildVASPAccount));
        Assert.assertEquals(500_000, Utils.getAccountBalance(client, receiverParentVASPAccount));
        Assert.assertEquals(1_000_000, Utils.getAccountBalance(client, receiverChildVASPAccount));

        // refund start: for a given transaction version
        List<JsonRpc.Transaction> txns = null;
        for (int retry = 100; retry > 0; retry--) {
            try {
                // include events, we will need event for refund reference id
                txns = client.getTransactions(transactionVersion, 1, true);
            } catch (StaleResponseException e) {
                // testnet is a cluster of full node, so we need handle stale response exception
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    throw new RuntimeException(ie);
                }
                continue;
            }
        }
        if (txns == null || txns.size() != 1) {
            throw new RuntimeException("could not find transaction: " + transactionVersion);
        }

        JsonRpc.Event event = TransactionMetadata.findRefundReferenceEventFromTransaction(txns.get(0), receiverChildVASPAccount.address);
        // event#data#currency is not set for receivedpayment event, should use amount#currency
        JsonRpc.Amount amount = event.getData().getAmount();
        Metadata metadata;
        try {
            metadata = TransactionMetadata.deserializeMetadata(event);
        } catch (DeserializationError e) {
            throw new RuntimeException("Can't deserialize received event metadata", e);
        }

        TransactionMetadata refundMetadata = TransactionMetadata.createRefundMetadataFromEvent(event.getSequenceNumber(), metadata);
        Utils.submitAndWait(client, receiverChildVASPAccount, Helpers.encode_peer_to_peer_with_metadata_script(
                CurrencyCode.typeTag(amount.getCurrency()),
                senderChildVASPAccount.address,
                amount.getAmount(),
                refundMetadata.getMetadata(),
                new Bytes(new byte[0])
        ));
        Assert.assertEquals(500_000, Utils.getAccountBalance(client, senderParentVASPAccount));
        Assert.assertEquals(500_000, Utils.getAccountBalance(client, senderChildVASPAccount));
        Assert.assertEquals(500_000, Utils.getAccountBalance(client, receiverParentVASPAccount));
        Assert.assertEquals(500_000, Utils.getAccountBalance(client, receiverChildVASPAccount));
    }
}
