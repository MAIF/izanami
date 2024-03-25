import clsx from "clsx";
import Heading from "@theme/Heading";
import styles from "./styles.module.css";

const FeatureList = [
  {
    title: "Agnostic",
    Svg: require("@site/static/img/undraw_around_the_world_re_rb1p.svg")
      .default,
    description: (
      <>
        Query your flag from any context using HTTP.
        <br />
      </>
    ),
  },
  {
    title: "Versatile",
    Svg: require("@site/static/img/undraw_switches_1js3.svg").default,
    description: (
      <>Activate your features at your conditions, whatever they are.</>
    ),
  },
  {
    title: "Open Source",
    Svg: require("@site/static/img/undraw_different_love_a-3-rg.svg").default,
    description: <>Suggest improvements or help making them happen !</>,
  },
];

function Feature({ Svg, title, description, reverse }) {
  return (
    <div className={styles.feature}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className={styles.details}>
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures() {
  return (
    <section
      style={{
        width: "100%",
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
      }}
    >
      <div className={styles.features}>
        {FeatureList.map((props, idx) => (
          <Feature key={idx} reverse={idx % 2 === 0} {...props} />
        ))}
      </div>
    </section>
  );
}
