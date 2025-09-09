  constructor(params: { chart: any; series: any; container: HTMLElement }) {
    super(params, {
      minPoints: 2,
      maxPoints: Number.POSITIVE_INFINITY,
      anchorRadiusPx: 4,
      hitTolerancePx: 6,
      showAnchorsWhenSelected: true,
      defaultProps: { color: "#1976d2", width: 1, style: "solid" },
    });
  }
