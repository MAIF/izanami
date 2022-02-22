import React, { Component } from "react";
import * as IzanamiServices from "../services/index";
import { Key, Table } from "../inputs";
import truncate from "lodash/truncate";
import AceEditor from "react-ace";

const ConfigValue = ({ id, value }) => {
  return (
    <AceEditor
      mode="javascript"
      name={`preview${id}`}
      theme="monokai"
      readOnly={true}
      minLines={1}
      maxLines={1}
      value={truncate(JSON.stringify(value), { length: 60 })}
      editorProps={{ $blockScrolling: true }}
      height="15px"
      annotations={[]}
      markers={[]}
      setOptions={{
        showGutter: false
      }}
      width="100%"
    />
  );
};

export class ConfigurationsPage extends Component {
  formSchema = {
    id: {
      type: "key",
      props: {
        label: "Configuration Id",
        placeholder: "The Configuration id",
        search(pattern) {
          return IzanamiServices.fetchConfigs({
            page: 1,
            pageSize: 20,
            search: pattern
          }).then(({ results }) => results.map(({ id }) => id));
        }
      },
      error: { key: "obj.id" }
    },
    value: {
      type: "json",
      props: { parse: true, label: "Value", placeholder: `true` },
      error: { key: "obj.value" }
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
    { title: "Name", content: item => <Key value={item.id} /> },
    {
      title: "Preview",
      content: item => <ConfigValue value={item.value} id={item.id} />
    }
  ];

  formFlow = ["id", "value"];

  fetchItems = args => {
    const { search = [], page, pageSize } = args;
    const pattern =
      search.length > 0
        ? search.map(({ id, value }) => `*${value}*`).join(",")
        : "*";
    return IzanamiServices.fetchConfigs({ page, pageSize, search: pattern });
  };

  fetchItemsTree = args => {
    const { search = [] } = args;
    const pattern =
      search.length > 0
        ? search.map(({ id, value }) => `*${value}*`).join(",")
        : "*";
    return IzanamiServices.fetchConfigsTree({ search: pattern });
  };

  fetchItem = id => {
    return IzanamiServices.fetchConfig(id);
  };

  createItem = ({ id, value }) => {
    return IzanamiServices.createConfig({ id, value: JSON.parse(value) });
  };

  updateItem = ({ id, value }, configOriginal) => {
    return IzanamiServices.updateConfig(configOriginal.id, {
      id,
      value: JSON.parse(value)
    });
  };

  deleteItem = config => {
    return IzanamiServices.deleteConfig(config.id);
  };

  componentDidMount() {
    this.props.setTitle("Configurations");
  }

  renderTreeLeaf = item => {
    return [
      <div
        key={`content-config-value-${item.id}`}
        className="content-value-items"
        style={{ width: 500 }}
      >
        <ConfigValue value={item.value} id={item.id} />
      </div>
    ];
  };

  itemLink = item => {
    return item && `/configurations/edit/${item.id}`;
  };

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              value: '{"key":"value"}',
              id: ""
            })}
            treeModeEnabled={true}
            renderTreeLeaf={this.renderTreeLeaf}
            itemLink={this.itemLink}
            user={this.props.user}
            parentProps={this.props}
            defaultTitle="Configurations"
            selfUrl="configurations"
            backToUrl="configurations"
            itemName="Configuration"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItemsTree={this.fetchItemsTree}
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            downloadLinks={[{ title: "Download", link: "/api/configs.ndjson" }]}
            uploadLinks={[
              { title: "Upload - replace if exists", link: "/api/configs.ndjson?strategy=Replace" },
              { title: "Upload - ignore if exists", link: "/api/configs.ndjson?strategy=Keep" }
            ]}
            showActions={true}
            showLink={false}
            eventNames={{
              created: "CONFIG_CREATED",
              updated: "CONFIG_UPDATED",
              deleted: "CONFIG_DELETED"
            }}
            extractKey={item => item.id}
            convertItem={({ id, value }) => ({
              id,
              value: JSON.stringify(value)
            })}
            navigate={this.props.navigate}
          />
        </div>
      </div>
    );
  }
}
