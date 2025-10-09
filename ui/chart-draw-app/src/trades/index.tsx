import React from "react";
import { TradesSummaryPage } from "./TradesSummaryPage";
import { TradeDetailPage } from "./TradeDetailPage";

// Export a route array you can spread into your Router definition.
export const tradesRoutes = [
  { path: "/trades", element: <TradesSummaryPage /> },
  { path: "/trades/:id", element: <TradeDetailPage /> },
];

export { TradesSummaryPage, TradeDetailPage };
