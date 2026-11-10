const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO Cache",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        {
          type: "category",
          label: "Reference",
          collapsed: false,
          items: [
            "cache",
            "scoped-cache",
            "lookup",
            "cache-statistics",
            "entry-statistics"
          ]
        },
        {
          type: "category",
          label: "Tutorial",
          collapsed: false,
          items: ["tutorial-first-cache"]
        },
        {
          type: "category",
          label: "How-to Guides",
          collapsed: false,
          items: [
            "how-to-choose-capacity-and-ttl",
            "how-to-per-entry-ttl",
            "how-to-cache-failures",
            "how-to-refresh-in-background",
            "how-to-cache-resources",
            "how-to-monitor-hit-rate"
          ]
        }
      ]
    }
  ]
};

module.exports = sidebars;
