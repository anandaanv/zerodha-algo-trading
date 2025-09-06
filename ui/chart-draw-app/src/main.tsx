import React from "react";
import { createRoot } from "react-dom/client";
import ProApp from "./pro/ProApp";
import "./styles.css";

const root = createRoot(document.getElementById("root")!);
root.render(<ProApp />);
