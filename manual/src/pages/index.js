import clsx from "clsx";
import Link from "@docusaurus/Link";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import Layout from "@theme/Layout";
import HomepageFeatures from "@site/src/components/HomepageFeatures";
import HomepageIzanamiDescription from "@site/src/components/HomepageIzanamiDescription";
import IzanamiImage from "@site/static/img/izanami_compressed.webp";

import Heading from "@theme/Heading";
import styles from "./index.module.css";

function HeroBanner() {
  return (
    <div className={styles.hero} data-theme="dark">
      <div className={styles.heroInner}>
        <div className={styles.heroInnerHead}>
          <Heading as="h1" className={styles.heroProjectTagline}>
            <span className={styles.heroTitleTextHtml}>
              Toggle and manage your <b>features</b> without changing your
              codebase.
            </span>
            <div className={styles.indexCtas}>
              <Link className="button button--primary" to="/docs/getstarted">
                Get Started
              </Link>
              <Link className="button button--secondary" to="/docs/usages">
                Documentation
              </Link>
            </div>
          </Heading>
          <img
            alt="Izanami as hand drawn character"
            className={styles.heroLogo}
            src={IzanamiImage}
          />
        </div>
      </div>
    </div>
  );
}

export default function Home() {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={`${siteConfig.title}`} description="Izanami documentation">
      <main>
        <HeroBanner />
      </main>
      {/* <HomepageHeader /> */}
      <main>
        <HomepageIzanamiDescription />
        <HomepageFeatures />
      </main>
    </Layout>
  );
}
