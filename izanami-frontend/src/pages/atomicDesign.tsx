export function AtomicDesign() {
  return (
    <>
      <p style={{ color: "var(--color-primary)" }}>
        Colors & bg-colors levels for Dark/White Mode
      </p>
      <div
        style={{
          width: 400,
          backgroundColor: "var(--bg-color_level1)",
          color: "var(--color_level1)",
          border: "1px solid",
          padding: 10,
        }}
      >
        --bg-color_level1 & --color_level1
        <div
          style={{
            width: 300,
            margin: "0 auto",
            backgroundColor: "var(--bg-color_level2)",
            color: "var(--color_level2)",
            padding: 10,
          }}
        >
          --bg-color_level2 & --color_level2
          <p
            style={{
              backgroundColor: "var(--bg-color_level25)",
              color: "var(--color_level3)",
              padding: 10,
            }}
          >
            --bg-color_level25
          </p>
          <div
            style={{
              width: 200,
              margin: "0 auto",
              backgroundColor: "var(--bg-color_level3)",
              color: "var(--color_level3)",
              padding: 10,
            }}
          >
            --bg-color_level3 & --color_level3
          </div>
        </div>
      </div>
    </>
  );
}
