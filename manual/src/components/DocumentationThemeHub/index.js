import clsx from "clsx";
import styles from "./styles.module.css";
import Link from "@docusaurus/Link";

const Section = ({ title, description, link }) => {
  return (
    <Link to={link} className={`${clsx("col col--5")} ${styles.section}`}>
      <h3 as="h3">{title}</h3>
      <p>{description}</p>
    </Link>
  );
};

export function DocumentationThemeHub({ list }) {
  return (
    <section className={styles.sections}>
      {list.map((props, idx) => (
        <Section key={idx} {...props} />
      ))}
    </section>
  );
}

export const DocumentationGuidesHub = () => (
  <DocumentationThemeHub
    list={[
      {
        title: "Architecture",
        description: "Know more about how Izanami works",
        link: "/docs/guides/archi",
      },
      {
        title: "Integration",
        description: "How to use integration API",
        link: "/docs/guides/integrations",
      },
      {
        title: "Apis",
        description: "How to use admin APIs",
        link: "/docs/guides/apis",
      },
      {
        title: "Deploy",
        description: "How to deploy your own Izanami",
        link: "/docs/guides/deploy",
      },
    ]}
  />
);
