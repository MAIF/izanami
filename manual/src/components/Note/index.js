import styles from "./styles.module.css";
import IzanamiLogo from "@site/static/img/izanami.png";

export function Note({ children }) {
  return (
    <div className={styles.description__trivia}>
      <img src={IzanamiLogo} />
      <div>{children}</div>
    </div>
  );
}
