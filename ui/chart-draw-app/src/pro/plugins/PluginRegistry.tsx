import React from "react";
import type { BaseOverlayPlugin } from "./BaseOverlayPlugin";

export type PluginCtor = new (params: { chart: any; series: any; container: HTMLElement }) => BaseOverlayPlugin;

export type PluginDefinition = {
  key: string;
  title: string;
  group: string;
  subgroup?: string;
  icon: () => JSX.Element;
  ctor: PluginCtor;
};

const registry: PluginDefinition[] = [];

export function registerPlugin(def: PluginDefinition) {
  const exists = registry.find((d) => d.key === def.key);
  if (!exists) registry.push(def);
}

export function getAllPlugins(): PluginDefinition[] {
  return registry.slice();
}

export function getPluginsByGroup(): Record<string, PluginDefinition[]> {
  const map: Record<string, PluginDefinition[]> = {};
  for (const def of registry) {
    if (!map[def.group]) map[def.group] = [];
    map[def.group].push(def);
  }
  return map;
}
