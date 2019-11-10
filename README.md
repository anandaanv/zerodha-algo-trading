# zerodha-algo-trading
A codebase to connect zerodha connect along with algo trading library to use power of algo trading with zerodha

Mission - 
Its not just zerodha, but this algo trading can apply to various platforms. What we are trying to do - 

1. Initialize framework to download historical data with with zerodha.
2. Initialize the framework to fetch a stock data along with its related scripts, like future and options. 
   While making any trading decisions, it very important to keen an eye on various not technical parameters - like Open interest, Future premium, Option chain etc, and also analyzing the movements of all these parameters remains very important for any day-trader. 
3. We try to keep it simple, and provide a StrategyBuilder and a BackTesting framework which includes all of these parameters, and makes it very easy for any strategy writer to make decisions.
   
   Powered by Ta4j, we have more than 200 Technical indicators, and we will keep ono contributing Ta4j to grow the list.
   
Present status - 
1. User can download avauilable data from Zerodha. You can create an application in zeordha that will login and redirect you back to our platform.
2. You can download all present instruments on exchange.
3. When you try to download a script from NSE, we also download its related data from NFO.
4. You can build a strategy using our Base-class and quickly backtest the same using another API provided. It returns evry good Json Response. 

Backlog - 
1. Current DataLoader loads NFO data for only current month. say e.g. If you take INFY, there can be many option series expring every month say current price is 700, then there will be series like INFYOCT19700CE, INFYSEP19700CE, INFYNOV19700CE, all those series should be counted as single series. 
    How to do that? Create a separate relationship table, that scans the instruments and finds out the relationships in Base script and derivatives and their Instruments. 
    Upon data requestm we can fetch all derivatives from same script, and merge the hostorical data based on expiry. This will make it very easier to backtest the data for any script.
 2. Ability to provide the option scripts as PEITM1, PEITM2, PEITM3 where PEITM1 is PutInTheMoneyNearest1, PEITM2 is PutInTheMoneyNearest2 and so on. This will make the strategy builders not to rely on extracting the names form scripts, but they can build a strategy based on standard variables. 
    This can actually help us to write our own scripting language in future. 
 3. Analyze and link missing edges in the tool
 4. Ability to put trade on behalf of user once a strategy is live.
   
   
