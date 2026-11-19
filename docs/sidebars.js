const sidebars = {
  sidebar: [
    "index",
    "getting-started",
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
    "tutorial-first-cache",
    {
      type: "category",
      label: "How-to Guides",
      collapsed: true,
      items: [
        "how-to-choose-capacity-and-ttl",
        "how-to-per-entry-ttl",
        "how-to-cache-failures",
        "how-to-refresh-in-background",
        "how-to-cache-resources",
        "how-to-monitor-hit-rate",
        "how-to-use-custom-cache-keys",
        "how-to-invalidate-on-write",
        "how-to-test-cache-expiry"
      ]
    },
    "resources"
  ]
};

module.exports = sidebars;
