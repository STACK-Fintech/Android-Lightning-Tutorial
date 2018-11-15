/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.acinq.eclair.wallet.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import org.bitcoinj.uri.BitcoinURI;
import org.greenrobot.eventbus.util.AsyncExecutor;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import fr.acinq.bitcoin.MilliSatoshi;
import fr.acinq.bitcoin.Satoshi;
import fr.acinq.bitcoin.package$;
import fr.acinq.eclair.CoinUnit;
import fr.acinq.eclair.CoinUtils;
import fr.acinq.eclair.payment.PaymentRequest;
import fr.acinq.eclair.wallet.App;
import fr.acinq.eclair.wallet.BuildConfig;
import fr.acinq.eclair.wallet.models.Payment;
import fr.acinq.eclair.wallet.models.PaymentDirection;
import fr.acinq.eclair.wallet.models.PaymentStatus;
import fr.acinq.eclair.wallet.models.PaymentType;
import fr.acinq.eclair.wallet.tasks.BitcoinInvoiceReaderTask;
import fr.acinq.eclair.wallet.utils.Constants;
import fr.acinq.eclair.wallet.utils.WalletUtils;

public class SendPaymentActivity extends AppCompatActivity
        implements BitcoinInvoiceReaderTask.AsyncInvoiceReaderTaskResponse {

    public static final String EXTRA_INVOICE = BuildConfig.APPLICATION_ID + "EXTRA_INVOICE";
    private static final String TAG = "SendPayment";
    private final static List<String> LIGHTNING_PREFIXES = Arrays.asList("lightning:", "lightning://");

    private boolean isProcessingPayment = false;
    private PaymentRequest mLNInvoice = null;
    private BitcoinURI mBitcoinInvoice = null;
    private String mInvoice = null;
    private boolean isAmountReadonly = true;

    private App app;
    private CoinUnit preferredBitcoinUnit = CoinUtils.getUnitFromString("btc");
    private String preferredFiatCurrency = Constants.FIAT_USD;
    // state of the fees, used with data binding
    private boolean capLightningFees = true;

//    @SuppressLint("SetTextI18n")
//    @Override
//    public void processLNInvoiceFinish(final PaymentRequest output) {
//        if (output == null) {
//            // try reading invoice as a bitcoin uri
//            new BitcoinInvoiceReaderTask(this, mInvoice).execute();
//        } else {
//            final Option<String> acceptedPrefix = PaymentRequest.prefixes().get(WalletUtils.getChainHash());
//            if (acceptedPrefix.isEmpty() || !acceptedPrefix.get().equals(output.prefix())) {
//                return;
//            }
//            // check lightning channels status
//            if (EclairEventService.getChannelsMap().size() == 0) {
//                return;
//            } else {
//                final Payment paymentInDB = app.getDBHelper().getPayment(output.paymentHash().toString(), PaymentType.BTC_LN);
//                if (paymentInDB != null && paymentInDB.getStatus() == PaymentStatus.PENDING) {
//                    return;
//                } else if (paymentInDB != null && paymentInDB.getStatus() == PaymentStatus.PAID) {
//                    return;
//                }
//            }
//            mLNInvoice = output;
//            isAmountReadonly = mLNInvoice.amount().isDefined();
//
//            if (isAmountReadonly) {
//                final MilliSatoshi amountMsat = WalletUtils.getAmountFromInvoice(mLNInvoice);
//                if (!EclairEventService.hasActiveChannelsWithBalance(amountMsat.amount())) {
//                    return;
//                }
//                // the amount can be overridden by the user to reduce information leakage, lightning allows payments to be overpaid
//                // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#requirements-2
//                // as such, the amount field stays editable.
//            }
//            Either<String, BinaryData> desc = output.description();
//        }
//    }

    @Override
    public void processBitcoinInvoiceFinish(final BitcoinURI output) {
        if (output == null || output.getAddress() == null) {
        } else {
            mBitcoinInvoice = output;
            isAmountReadonly = mBitcoinInvoice.getAmount() != null;
            if (isAmountReadonly) {
                final MilliSatoshi amountMsat = package$.MODULE$.satoshi2millisatoshi(mBitcoinInvoice.getAmount());
            }
        }
    }

    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        preferredBitcoinUnit = WalletUtils.getPreferredCoinUnit(sharedPref);
        preferredFiatCurrency = WalletUtils.getPreferredFiat(sharedPref);
        capLightningFees = sharedPref.getBoolean(Constants.SETTING_CAP_LIGHTNING_FEES, true);

        // --- read invoice from intent
        final Intent intent = getIntent();
        mInvoice = intent.getStringExtra(EXTRA_INVOICE).trim();
        Log.d(TAG, "Initializing payment with invoice=" + mInvoice);
        if (mInvoice != null) {
            for (String prefix : LIGHTNING_PREFIXES) {
                if (mInvoice.toLowerCase().startsWith(prefix)) {
                    mInvoice = mInvoice.substring(prefix.length());
                    break;
                }
            }
        }
    }

    /**
     * Executes a Lightning payment in an asynchronous task.
     *
     * @param amountMsat amount of the payment in milli satoshis
     * @param pr         lightning payment request
     * @param prAsString payment request as a string (used for display)
     */
    public void sendLNPayment(final long amountMsat, final PaymentRequest pr, final String prAsString) {
        final String paymentHash = pr.paymentHash().toString();
        AsyncExecutor.create().execute(
                () -> {
                    final String paymentDescription = pr.description().isLeft() ? pr.description().left().get() : pr.description().right().get().toString();
                    final Payment newPayment = new Payment();
                    newPayment.setType(PaymentType.BTC_LN);
                    newPayment.setDirection(PaymentDirection.SENT);
                    newPayment.setReference(paymentHash);
                    newPayment.setAmountRequestedMsat(WalletUtils.getLongAmountFromInvoice(pr));
                    newPayment.setAmountSentMsat(amountMsat);
                    newPayment.setRecipient(pr.nodeId().toString());
                    newPayment.setPaymentRequest(prAsString.toLowerCase());
                    newPayment.setStatus(PaymentStatus.INIT);
                    newPayment.setDescription(paymentDescription);
                    newPayment.setUpdated(new Date());

                    // execute payment future, with cltv expiry + 1 to prevent the case where a block is mined just
                    // when the payment is made, which would fail the payment.
                    Log.i(TAG, "sending " + amountMsat + " msat for invoice " + prAsString);
                    app.sendLNPayment(pr, amountMsat, capLightningFees);
                }
        );
    }

    /**
     * Sends a Bitcoin transaction.
     *
     * @param amountSat  amount of the tx in satoshis
     * @param feesPerKw  fees to the network in satoshis per kb
     * @param bitcoinURI contains the bitcoin address
     */
    private void sendBitcoinPayment(final Satoshi amountSat, final Long feesPerKw, final BitcoinURI bitcoinURI, final boolean emptyWallet) {
        Log.i(TAG, "sending " + amountSat + " sat invoice " + mBitcoinInvoice.toString());
        if (emptyWallet) {
            Log.i(TAG, "sendBitcoinPayment: emptying wallet with special method....");
            app.sendAllOnchain(bitcoinURI.getAddress(), feesPerKw);
        } else {
            app.sendBitcoinPayment(amountSat, bitcoinURI.getAddress(), feesPerKw);
        }
    }
}
