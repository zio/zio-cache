const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO Cache",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        "lookup",
        "cache",
        "scoped-cache",
        "cache-statistics",
        "entry-statistics"
      ]
    }
  ]
};

module.exports = sidebars;
