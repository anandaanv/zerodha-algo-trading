import React from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import ProApp from "./pro/ProApp";

const root = createRoot(document.getElementById("root")!);
root.render(<ProApp />);
