import React, { Component } from "react";
import * as IzanamiServices from "../services/index";
import { Table } from "../inputs";
import * as ScriptsTemplate from "../helpers/ScriptTemplates";
import { JsLogo, KotlinLogo, ScalaLogo } from "../components/Logos";

export class GlobalScriptsPage extends Component {
  formSchema = {
    id: {
      type: "string",
      props: { label: "Script Id", placeholder: "The Script id" },
      error: { key: "obj.id" }
    },
    name: {
      type: "string",
      props: { label: "Script name", placeholder: "The Script name" },
      error: { key: "obj.name" }
    },
    description: {
      type: "string",
      props: {
        label: "Script description",
        placeholder: "The Script description"
      },
      error: { key: "obj.description" }
    },
    source: {
      type: "code",
      props: {
        label: "Code",
        placeholder: `true`,
        debug: true,
        languages: {
          javascript: {
            label: "Javascript",
            snippet: ScriptsTemplate.javascriptDefaultScript
          },
          scala: {
            label: "Scala",
            snippet: ScriptsTemplate.scalaDefaultScript
          },
          kotlin: {
            label: "Kotlin",
            snippet: ScriptsTemplate.kotlinDefaultScript
          }
        }
      },
      error: { key: "obj.source" }
    }
  };

  editSchema = {
    ...this.formSchema,
    id: {
      ...this.formSchema.id,
      props: { ...this.formSchema.id.props, disabled: true }
    }
  };

  columns = [
    { title: "Id", content: item => item.id },
    {
      title: "Language",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => {
        if (item.source.type === "javascript") {
          return (
            <span>
              <JsLogo width={"20px"} />
              {` Script`}
            </span>
          );
        } else if (item.source.type === "scala") {
          return (
            <span>
              <ScalaLogo width={"20px"} />
              {` Script`}
            </span>
          );
        } else if (item.source.type === "kotlin") {
          return (
            <span>
              <KotlinLogo width={"20px"} />
              {` Script`}
            </span>
          );
        }
      }
    },
    {
      title: "Name",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => item.name
    },
    {
      title: "Description",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => item.description
    }
  ];

  formFlow = ["id", "name", "description", "source"];

  fetchItems = args => {
    const { search = [], page, pageSize } = args;
    const pattern =
      search.length > 0
        ? search.map(({ id, value }) => `*${value}*`).join(",")
        : "*";
    return IzanamiServices.fetchScripts({ page, pageSize, search: pattern });
  };

  fetchItem = id => {
    return IzanamiServices.fetchScript(id);
  };

  createItem = script => {
    return IzanamiServices.createScript(script);
  };

  updateItem = (script, scriptOriginal) => {
    return IzanamiServices.updateScript(scriptOriginal.id, script);
  };

  deleteItem = script => {
    return IzanamiServices.deleteScript(script.id, script);
  };

  componentDidMount() {
    this.props.setTitle("Scripts");
  }

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              id: "project:script1",
              name: "Authorization Script",
              description: "Authorize all members of the project team",
              source: {
                type: "javascript",
                script: ScriptsTemplate.javascriptDefaultScript
              }
            })}
            user={this.props.user}
            parentProps={this.props}
            defaultTitle="Scripts"
            selfUrl="scripts"
            backToUrl="scripts"
            itemName="Script"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            downloadLinks={[{ title: "Download", link: "/api/scripts.ndjson" }]}
            uploadLinks={[
              { title: "Upload - replace if exists", link: "/api/scripts.ndjson?strategy=Replace" },
              { title: "Upload - ignore if exists", link: "/api/scripts.ndjson?strategy=Keep" }
            ]}
            showActions={true}
            eventNames={{
              created: "GLOBALSCRIPT_CREATED",
              updated: "GLOBALSCRIPT_UPDATED",
              deleted: "GLOBALSCRIPT_DELETED"
            }}
            showLink={false}
            extractKey={item => item.id}
          />
        </div>
      </div>
    );
  }
}
