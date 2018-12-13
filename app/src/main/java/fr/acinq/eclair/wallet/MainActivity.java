package fr.acinq.eclair.wallet;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.typesafe.config.ConfigFactory;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.util.AsyncExecutor;

import java.io.File;
import java.util.List;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.OnComplete;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import fr.acinq.bitcoin.BinaryData;
import fr.acinq.bitcoin.MilliSatoshi;
import fr.acinq.bitcoin.MnemonicCode;
import fr.acinq.bitcoin.Satoshi;
import fr.acinq.bitcoin.package$;
import fr.acinq.eclair.CoinUnit;
import fr.acinq.eclair.CoinUtils;
import fr.acinq.eclair.Kit;
import fr.acinq.eclair.Setup;
import fr.acinq.eclair.blockchain.electrum.ElectrumEclairWallet;
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet;
import fr.acinq.eclair.channel.ChannelEvent;
import fr.acinq.eclair.io.NodeURI;
import fr.acinq.eclair.io.Peer;
import fr.acinq.eclair.payment.PaymentLifecycle;
import fr.acinq.eclair.payment.PaymentRequest;
import fr.acinq.eclair.wallet.activities.SendPaymentActivity;
import fr.acinq.eclair.wallet.events.LNNewChannelFailureEvent;
import fr.acinq.eclair.wallet.events.LNNewChannelOpenedEvent;
import fr.acinq.eclair.wallet.utils.Constants;
import fr.acinq.eclair.wallet.utils.WalletUtils;
import scala.Option;
import scala.collection.JavaConverters;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

public class MainActivity extends AppCompatActivity {
    private App app;
    public final static String SEED_NAME = "enc_seed.dat";
    private CoinUnit preferredBitcoinUnit = CoinUtils.getUnitFromString(Constants.BTC_CODE);
    private final String nodeURIAsString = "034fe52e98a0e9d3c21b767e1b371881265d8c7578c21f5afd6d6438da10348b36@23.239.23.44:9735";
//    private final String nodeURIAsString = "036a83ffecf0be323b08f33a03d02f28f226ec3cf4315fb43f4c6a374d8ead6bba@159.89.214.31:9735";
    private NodeURI remoteNodeURI = NodeURI.parse(nodeURIAsString);

    @BindView(R.id.main_ll)
    LinearLayout mainLl;
    @BindView(R.id.balance_tv)
    TextView balanceTv;
    @BindView(R.id.connected_tv)
    TextView connectedTv;
    @BindView(R.id.uri_et)
    EditText uriEt;
    @BindView(R.id.deposit_et)
    EditText depositEt;
    @BindView(R.id.amount_et)
    EditText amountEt;
    @BindView(R.id.payment_et)
    EditText paymentEt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        initKeyboard();
        app = ((App) getApplication());
        initWalletNode(((App) getApplication()));
    }

    private void hideKeyboard() {
        View v = this.getWindow().getCurrentFocus();
        if (v != null) {
            InputMethodManager imm = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    private void initKeyboard() {
        uriEt.setImeOptions(EditorInfo.IME_ACTION_DONE);
        depositEt.setImeOptions(EditorInfo.IME_ACTION_DONE);
        amountEt.setImeOptions(EditorInfo.IME_ACTION_DONE);
        paymentEt.setImeOptions(EditorInfo.IME_ACTION_DONE);

        uriEt.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
                if(actionId == EditorInfo.IME_ACTION_DONE){
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        depositEt.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
                if(actionId == EditorInfo.IME_ACTION_DONE){
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        amountEt.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
                if(actionId == EditorInfo.IME_ACTION_DONE){
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
        paymentEt.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event){
                if(actionId == EditorInfo.IME_ACTION_DONE){
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });
    }

    private void initWalletNode(App... params) {
        try {
            App app = params[0];
            final File datadir = new File(app.getFilesDir(), Constants.ECLAIR_DATADIR);
            final BinaryData bSeed = BinaryData.apply(new String(getWallet(datadir)));

            Log.d("seed", bSeed.toString());

            app.checkupInit();

            Class.forName("org.sqlite.JDBC");
            final Setup setup = new Setup(datadir, Option.apply(null), ConfigFactory.empty(), this.app.system, Option.apply(bSeed));

            ActorRef guiUpdater = app.system.actorOf(Props.create(EclairEventService.class, app.getDBHelper()));
            setup.system().eventStream().subscribe(guiUpdater, ChannelEvent.class);
            setup.system().eventStream().subscribe(guiUpdater, PaymentLifecycle.PaymentResult.class);
            app.system.actorOf(Props.create(PaymentSupervisor.class, app.getDBHelper()), "payments");

            Future<Kit> fKit = setup.bootstrap();
            Kit kit = Await.result(fKit, Duration.create(120, "seconds"));
            ElectrumEclairWallet electrumWallet = (ElectrumEclairWallet) kit.wallet();

            Future<ElectrumWallet.GetXpubResponse>  pub = electrumWallet.getXpub();
            Log.d("pub", String.valueOf(pub));

            this.app.appKit = new App.AppKit(electrumWallet, kit, true);
//            final MilliSatoshi walletBalance = this.app == null ? new MilliSatoshi(0) : package$.MODULE$.satoshi2millisatoshi(this.app.getOnchainBalance());
            final Satoshi walletBalance = this.app == null ? new Satoshi(0) : this.app.getOnchainBalance();
            Log.d("balance", String.valueOf(walletBalance.amount()));
            balanceTv.setText(String.valueOf(walletBalance.amount()));

//            Handler handler = new Handler();
//            handler.postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    Log.d("address", app.getWalletAddress());
//                }
//            }, 20000);

        }
        catch (Exception e) {
            Log.d("error_balance", e.getMessage());
        }
    }

    private byte[] getWallet(final File datadir) {
        final File seedFile = new File(datadir, SEED_NAME);
        if (!seedFile.exists()) {
            List<String> mnemonic = generateMnemonic();
            byte[] seed = MnemonicCode.toSeed(JavaConverters.collectionAsScalaIterableConverter(mnemonic).asScala().toSeq(), "").toString().getBytes();
            try {
                WalletUtils.writeSeedFile(datadir, seed, "123456");
            } catch (Exception e) {
                return null;
            }
            return seed;
        } else {
            try {
                return WalletUtils.readSeedFile(datadir, "123456");
            } catch (Exception e) {
                return null;
            }
        }
    }

    private List<String> generateMnemonic() {
        try {
            return JavaConverters.seqAsJavaListConverter(MnemonicCode.toMnemonics(fr.acinq.eclair.package$.MODULE$.randomBytes(
                    ElectrumWallet.SEED_BYTES_LENGTH()).data(), MnemonicCode.englishWordlist())).asJava();
        } catch (Exception e) {

        }

        return null;
    }

    private void doOpenChannel(String deposit) {
        final Satoshi fundingSat = CoinUtils.convertStringAmountToSat(deposit, preferredBitcoinUnit.code());
        final Long feesPerKw = fr.acinq.eclair.package$.MODULE$.feerateByte2Kw(Long.parseLong("3"));
        try {
            Peer.OpenChannel o = new Peer.OpenChannel(remoteNodeURI.nodeId(), fundingSat, new MilliSatoshi(0), scala.Option.apply(feesPerKw), scala.Option.apply(null));
            AsyncExecutor.create().execute(
                    () -> {
                        OnComplete<Object> onComplete = new OnComplete<Object>() {
                            @Override
                            public void onComplete(Throwable throwable, Object o) throws Throwable {
                                if (throwable != null && throwable instanceof akka.pattern.AskTimeoutException) {
                                    // future timed out, do not display message
                                } else if (throwable != null) {
                                    Log.d("bolt action", "failed");
                                    EventBus.getDefault().post(new LNNewChannelFailureEvent(throwable.getMessage()));
                                } else {
                                    Log.d("bolt action", "succeeded");
                                    uriEt.setText("");
                                    depositEt.setText("");
                                    connectedTv.setTextColor(getResources().getColor(R.color.green));
                                    EventBus.getDefault().post(new LNNewChannelOpenedEvent(remoteNodeURI.nodeId().toString()));
                                }
                            }
                        };
                        app.openChannel(Duration.create(2000, "seconds"), onComplete, remoteNodeURI, o);
                    });
        } catch (Throwable t) {

        }
    }

    @OnClick(R.id.connect_btn)
    void connectButtonClick() {
        remoteNodeURI = NodeURI.parse(uriEt.getText().toString());
        String deposit = depositEt.getText().toString();
        doOpenChannel(deposit);
    }

    @OnClick(R.id.payment_btn)
    void paymentButton() {
        try {
            String invoice = paymentEt.getText().toString();
            PaymentRequest pr = PaymentRequest.read(invoice);
            final MilliSatoshi mSatAmount = WalletUtils.getAmountFromInvoice(pr);
            app.sendLNPayment1(mSatAmount.toLong(), pr, invoice);
            SendPaymentActivity sendPaymentActivity = new SendPaymentActivity();
            sendPaymentActivity.sendLNPayment(mSatAmount.toLong(), pr, invoice);
            balanceTv.setText(String.valueOf(this.app == null ? new Satoshi(0) : this.app.getOnchainBalance()));
        }
        catch(Exception e) {

        }
    }

    @OnClick(R.id.close_btn)
    void closeButton() {

    }
}
