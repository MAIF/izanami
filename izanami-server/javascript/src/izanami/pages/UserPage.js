import React, { Component } from "react";
import * as IzanamiServices from "../services/index";
import {Table, TextInput} from "../inputs";
import isEqual from "lodash/isEqual";
import {AuthorizedPatterns} from "../inputs";


class StringOrDisabled extends Component {
  render() {
    const type = this.props.source.type;
    if (type === 'Izanami') {
      return <TextInput {...this.props} disabled={false}/>
    } else if (this.props.hide) {
      return <div/>;
    } else {
      return <TextInput {...this.props} disabled={true}/>
    }
  }
}

export class UserPage extends Component {
  formSchema = {
    id: {
      type: StringOrDisabled,
      props: { label: "User Id", placeholder: "The User id"}
    },
    name: {
      type: StringOrDisabled,
      props: { label: "User Name", placeholder: "The User name"}
    },
    email: {
      type: StringOrDisabled,
      props: { label: "User email", placeholder: "The User email"}
    },
    password: {
      type: StringOrDisabled,
      props: {
        label: "User password",
        placeholder: "The User password",
        type: "password",
        hide: true
      }
    },
    admin: {
      type: "bool",
      props: { label: "is admin", placeholder: "Is admin?" }
    },
    authorizedPatterns: {
      type: "authorizedPatterns",
      props: {
        label: "User authorized pattern",
        placeholder: "The User authorized pattern"
      }
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
      title: "Name",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => item.name
    },
    {
      title: "Email",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => item.email
    },
    {
      title: "Authorized pattern",
      notFilterable: true,
      style: { textAlign: "center" },
      content: item => <AuthorizedPatterns value={item.authorizedPatterns}/>
    }
  ];

  formFlow = ["id", "name", "email", "password", "admin", "authorizedPatterns"];

  fetchItems = args => {
    const { search = [], page, pageSize } = args;
    const pattern =
      search.length > 0
        ? search.map(({ id, value }) => `*${value}*`).join(",")
        : "*";
    return IzanamiServices.fetchUsers({ page, pageSize, search: pattern });
  };

  fetchItem = id => {
    return IzanamiServices.fetchUser(id);
  };

  createItem = user => {
    return IzanamiServices.createUser(user);
  };

  updateItem = (user, userOriginal) => {
    return IzanamiServices.updateUser(userOriginal.id, user);
  };

  deleteItem = user => {
    return IzanamiServices.deleteUser(user.id, user);
  };

  componentDidMount() {
    this.props.setTitle("Users");
  }

  render() {
    return (
      <div className="col-md-12">
        <div className="row">
          <Table
            defaultValue={() => ({
              type: "Izanami",
              id: "john.doe@maif.fr",
              name: "John Doe",
              email: "john.doe@maif.fr",
              password: null,
              admin: true,
              authorizedPatterns: [{pattern:"*", rights:["C", "R", "U", "D"]}]
            })}
            parentProps={this.props}
            user={this.props.user}
            defaultTitle="Users"
            selfUrl="users"
            backToUrl="users"
            itemName="user"
            formSchema={this.formSchema}
            editSchema={this.editSchema}
            formFlow={this.formFlow}
            columns={this.columns}
            fetchItems={this.fetchItems}
            fetchItem={this.fetchItem}
            updateItem={this.updateItem}
            deleteItem={this.deleteItem}
            createItem={this.createItem}
            downloadLinks={[{ title: "Download", link: "/api/users.ndjson" }]}
            uploadLinks={[
              { title: "Upload - replace if exists", link: "/api/users.ndjson?strategy=Replace" },
              { title: "Upload - ignore if exists", link: "/api/users.ndjson?strategy=Keep" }
            ]}
            showActions={true}
            disableAddButton={this.props.userManagementMode === "OAuth"}
            showLink={false}
            eventNames={{
              created: "USER_CREATED",
              updated: "USER_UPDATED",
              deleted: "USER_DELETED"
            }}
            compareItem={(a, b) => {
              const { password: p1, ...rest1 } = a;
              const { password: p2, ...rest2 } = b;
              return isEqual(rest1, rest2);
            }}
            extractKey={item => item.id}
            navigate={this.props.navigate}
          />
        </div>
      </div>
    );
  }
}
