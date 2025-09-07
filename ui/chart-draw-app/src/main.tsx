import React from "react";
import { createRoot } from "react-dom/client";
import ProApp from "./pro/ProApp";
import "./styles.css";
import KLineApp from "./kline/KLineApp";

const root = createRoot(document.getElementById("root")!);
root.render(<KLineApp />);
