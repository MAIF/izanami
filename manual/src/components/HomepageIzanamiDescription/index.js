import clsx from "clsx";
import Heading from "@theme/Heading";
import styles from "./styles.module.css";

export default function HomepageIzanamiDescription() {
  return (
    <section className={styles.features} data-theme="light">
      <div className="container">
        <div
          className={styles.description__main}
          style={{
            textAlign: "center",
            fontSize: "27px",
            fontWeight: "bold",
            marginBottom: "-8px",
          }}
        >
          Izanami is an open source centralized feature flag solution.
        </div>
        <div
          className={styles.description__main}
          style={{ textAlign: "center", fontSize: "24px" }}
        >
          Manage your flags in one place, query them from anywhere.
          {/*<br />
          Izanami exposes flags through an{" "}
          <a href="#" target="_blank">
            HTTP API
          </a>{" "}
          and{" "}
          <a href="#" target="_blank">
            specific clients
          </a>{" "}
          <br />
          allowing them to be queried from anywhere in your information system.
  */}
        </div>
      </div>
    </section>
  );
}
