# zerodha-algo-trading
A codebase to connect zerodha connect along with algo trading library to use power of algo trading with zerodha

**IMP: Any new code additions should go to package `com.dtech.algo`. This package is supposed to have 100% code coverage production ready.**

**I am looking for React/ Flutter developers to build a frontend app for this project**

We started with simple MVP for fetching data from Zerodha and putting trades. When it was successful, we went ahead with 
building a fully working algo trading app.

* What do we want to build?

We want to build a solution where users can build a strategies that can 
together work on different segments and make a comparison analysis in Equity, Derivatives before 
Putting trade. E.g. I want to buy SBIN Fut, but the best criteria to make that call is 
analysing open interest in the nearest In the money Put. We want to build that level of mechanism, which no one provides as of now.

* What do we have as of now?

After first MVP, now we have built a server side architecture to make fully configurable strategies using 
  different bar-series for technical analysis and different bar-series for trades. So now you can make a trade in 
  SBIN Fut Or SBI Cash by analysing Open interest in SBIN call and puts, or PE ratio,

* What is pending?

We have a huge backlog, because we want to build a market ready product. Some of them are 
1. Build a usable web/mobile app that can be used by our users to build strategy
2. Integrate with zerodha websockets for now for putting trades in realtime.
3. Take the library to next level with integrating with different brokers.
These are few things from the top of my mind, and the list is ever-growing!!
   
**We welcome all sort of contributions, including your time and money!!**