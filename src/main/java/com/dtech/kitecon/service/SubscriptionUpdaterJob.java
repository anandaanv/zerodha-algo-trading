package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * EOD job — runs at market close (3:30 PM IST, Mon–Fri).
 * Syncs all FNO stock symbols across all relevant timeframes directly via DataFetchService.
 * Independent of subscription config — always covers the full FNO universe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionUpdaterJob {

    private static final List<Interval> EOD_TIMEFRAMES = List.of(
            Interval.Day,
            Interval.OneHour,
            Interval.FifteenMinute,
            Interval.FiveMinute,
            Interval.ThirtyMinute,
            Interval.FourHours,
            Interval.Week
    );

    private static final List<String> INDEX_FUTURES = List.of(
            "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX", "NIFTYNXT50"
    );

    private static final List<String> NIFTY_500 = List.of(
            "360ONE","3MINDIA","ABB","ACC","AIAENG","APLAPOLLO","AUBANK","AWL","AADHARHFC",
            "AAVAS","ABCAPITAL","ABFRL","ABREL","ABSLAMC","ACE","ACMESOLAR","ACUTAAS",
            "ADANIENSOL","ADANIENT","ADANIGREEN","ADANIPORTS","ADANIPOWER","ATGL","AEGISLOG",
            "AEGISVOPAK","AFCONS","AFFLE","AJANTPHARM","AKZOINDIA","ALKEM","ALKYLAMINE",
            "ALLCARGO","AMARAJABAT","AMBUJACEM","ANGELONE","ANURAS","APOLLOHOSP","APOLLOTYRE",
            "APTUS","ASAHIINDIA","ASIANPAINT","ASTERDM","ASTRAZEN","ATPL","AUROBINDO",
            "AVANTIFEED","AXIBANK","AXISBANK","BAJAJ-AUTO","BAJAJFINSV","BAJAJHFL","BAJFINANCE",
            "BALKRISIND","BALRAMCHIN","BANDHANBNK","BANKBARODA","BATAINDIA","BAYERCROP",
            "BERGEPAINT","BEL","BHARATFORG","BHEL","BHARTIARTL","BIOCON","BIRLACORPN",
            "BLUEDART","BLUEKNIGHT","BLUESTARCO","BPCL","BRIGADE","BRITANNIA","BSOFT",
            "CAMS","CANFINHOME","CAPLIPOINT","CARBORUNIV","CASTROLIND","CEATLTD","CENTURYTEX",
            "CESC","CGPOWER","CHALET","CHAMBLFERT","CHOLAFIN","CIEINDIA","CIPLA","COALINDIA",
            "COCHINSHIP","COFORGE","COLPAL","CONCOR","COROMANDEL","CPPLUS","CREDITACC",
            "CRISIL","CROMPTON","CSBBANK","CUMMINSIND","CYIENT","DALBHARAT","DATAPATTNS",
            "DEEPAKNTR","DELHIVERY","DEVYANI","DIXON","DLF","DMART","DNAVS","DRREDDY",
            "DIVISLAB","EDELWEISS","ELGIEQUIP","EMAMILTD","ENDURANCE","ENGINERSIN",
            "EICHERMOT","EPL","EQUITASBNK","ESCORTS","EXIDEIND","FACT","FCONSUMER",
            "FEDERALBNK","FINEORG","FINPIPE","FIVESTAR","FORCEMOT","FORTIS","GAIL",
            "GALAXYSURF","GILLETTE","GLENMARK","GMRAIRPORT","GODREJCP","GODREJIND",
            "GODREJPROP","GRANULES","GRAPHITE","GRASIM","GRINDWELL","GSFC","GUJALKALI",
            "GUJGASLTD","HAPPYFORGE","HAPPSTMNDS","HATSUN","HAVELLS","HCLTECH","HDFCAMC",
            "HDFCBANK","HDFCLIFE","HEROMOTOCO","HINDALCO","HINDCOPPER","HINDPETRO",
            "HINDUNILVR","HONASA","HONAUT","HUDCO","IBREALEST","ICICIBANK","ICICIGI",
            "ICICIPRULI","IDBI","IDFCFIRSTB","IEX","IGL","IIFL","IIMPORTS","INDHOTEL",
            "INDIAMART","INDIGO","INDUSINDBK","INDUSTOWER","INFY","INOXWIND","IOB",
            "IOCL","IRCTC","IRFC","ISEC","ITC","ITI","J&KBANK","JBCHEPHARM","JKTYRE",
            "JMFINANCIL","JSWENERGY","JSWSTEEL","JUBLFOOD","JUBLINGREA","JUSTDIAL",
            "JYOTHYLAB","KAJARIACER","KALPATPOWR","KALYANKJIL","KANSAINER","KAYNES",
            "KFINTECH","KNRCON","KOTAKBANK","KPIL","L&TFH","LALPATHLAB","LAURUSLABS",
            "LICHSGFIN","LICI","LODHA","LT","LTIM","LTTS","LUPIN","M&M","M&MFIN",
            "MARICO","MARUTI","MASTEK","MAXHEALTH","MAZDOCK","MCX","METROPOLIS",
            "MFSL","MGL","MIDHANI","MMTC","MNRE","MPHASIS","MRF","MUTHOOTFIN",
            "NATCOPHARM","NATIONALUM","NAUKRI","NAVINFLUOR","NESTLEIND","NHPC","NLCINDIA",
            "NMDC","NTPC","NUVOCO","OBEROIRLTY","OFSS","OIL","ONGC","PAGEIND","PEL",
            "PERSISTENT","PETRONET","PFC","PFIZER","PHOENIXLTD","PIDILITIND","PIIND",
            "POLICYBZR","POLYCAB","POLYMED","POONAWALLA","POWERGRID","PRAJIND","PRESTIGE",
            "PRINCEPIPE","PRISM","PVRINOX","QUESS","RADICO","RAILTEL","RAINBOW",
            "RAJESHEXPO","RKFORGE","RITES","ROSSARI","ROUTE","RPOWER","SANOFI",
            "SAPPHIRE","SAREGAMA","SBICARD","SBILIFE","SBIN","SCHAEFFLER","SEQUENT",
            "SHRIRAMFIN","SIEMENS","SKFINDIA","SOBHA","SOLARA","SONACOMS","SPANDANA",
            "SPARC","SRTRANSFIN","STARHEALTH","STLTECH","SUBROS","SUNDARMFIN","SUNDRMFAST",
            "SUNPHARMA","SUNTV","SUPREMEIND","SUVENPHAR","SUZLON","SWANENERGY","SYMPHONY",
            "TANLA","TATACHEM","TATACOMM","TATACONSUM","TATAELXSI","TATAMOTORS","TATAPOWER",
            "TATASTEEL","TATATECH","TCI","TCNSCLOTHING","TEAMLEASE","TECHM","THERMAX",
            "TIMKEN","TITAGARH","TITAN","TORNTPHARM","TORNTPOWER","TRENT","TTML","TVSHLTD",
            "UBL","ULTRACEMCO","UNIONBANK","UNITDSPR","UPCL","UPL","UTIAMC","VAIBHAVGBL",
            "VBL","VEDL","VENUSMED","VIJAYA","VOLTAS","VSTIND","WELCORP","WELSPUNLIV",
            "WIPRO","WOCKPHARMA","YESBANK","ZEEL","ZENSARTECH","ZOMATO","ZYDUSLIFE","ZYDUSWEL"
    );

    private final DataFetchService dataFetchService;
    private final InstrumentRepository instrumentRepository;

    // Enable/disable via API if needed
    @Setter
    @Getter
    private volatile boolean enabled = true;

    /**
     * Run at market close (3:30 PM IST) on weekdays.
     * Cron can be overridden via data.update.eodCron property.
     */
    @Scheduled(cron = "${data.update.eodCron:0 30 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void runUpdateJob() {
        if (!enabled) {
            log.info("[EOD] Job disabled — skipping.");
            return;
        }

        // Refresh instrument master first
        try {
            dataFetchService.downloadAllInstruments();
        } catch (Throwable e) {
            log.warn("[EOD] Instrument download failed: {}", e.getMessage());
        }

        Set<String> symbolSet = new LinkedHashSet<>();
        symbolSet.addAll(NIFTY_500);
        instrumentRepository.findDistinctFutureUnderlyingNamesWithNseEquity()
                .stream()
                .filter(s -> s != null && !s.isBlank() && !INDEX_FUTURES.contains(s.toUpperCase()))
                .forEach(symbolSet::add);
        List<String> symbols = new ArrayList<>(symbolSet);
        Collections.sort(symbols);

        log.info("[EOD] Starting sync for {} FNO symbols × {} timeframes", symbols.size(), EOD_TIMEFRAMES.size());

        int success = 0, errors = 0;
        for (String symbol : symbols) {
            for (Interval tf : EOD_TIMEFRAMES) {
                try {
                    dataFetchService.updateInstrumentToLatest(symbol, tf, new String[]{"NSE"});
                    success++;
                } catch (Exception e) {
                    errors++;
                    log.warn("[EOD] Failed {}/{}: {}", symbol, tf, e.getMessage());
                }
            }
        }

        log.info("[EOD] Sync complete — success={} errors={}", success, errors);
    }

}
