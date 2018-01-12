import React, { Component } from 'react';
import * as IzanamiServices from "../services/index";
import { Table } from '../inputs';

export class UserPage extends Component {


  formSchema = {
    id: { type: 'string', props: { label: 'User Id', placeholder: 'The User id' }},
    name: { type: 'string', props: { label: 'User Name', placeholder: 'The User name' }},
    email: { type: 'string', props: { label: 'User email', placeholder: 'The User email' }},
    password: { type: 'string', props: { label: 'User password', placeholder: 'The User password', type: 'password' }},
    admin: { type: 'bool', props: { label: 'is admin', placeholder: 'Is admin?' }},
    authorizedPattern: { type: 'string', props: { label: 'User authorized pattern', placeholder: 'The User authorized pattern' }},
  };

  editSchema = { ...this.formSchema, id: { ...this.formSchema.id, props: { ...this.formSchema.id.props, disabled: true } } };

  columns = [
    { title: 'Id', content: item => item.id },
    { title: 'Name', notFilterable: true, style: { textAlign: 'center'}, content: item => item.name },
    { title: 'Email', notFilterable: true, style: { textAlign: 'center'}, content: item => item.email },
    { title: 'Authorized pattern', notFilterable: true, style: { textAlign: 'center'}, content: item => item.authorizedPattern},
  ];

  formFlow = [
    'id',
    'name',
    'email',
    'password',
    'admin',
    'authorizedPattern'
  ];

  fetchItems = (args) => {
    const {search = [], page, pageSize} = args;
    const pattern = search.length>0 ? search.map(({id, value}) => `*${value}*`).join(",")  : "*"
    return IzanamiServices.fetchUsers({page, pageSize, search: pattern }); 
  };

  fetchItem = (id) => {
    return IzanamiServices.fetchUser(id);
  };

  createItem = (user) => {
    return IzanamiServices.createUser(user);
  };

  updateItem = (user, userOriginal) => {
    return IzanamiServices.updateUser(userOriginal.id, user);
  };

  deleteItem = (user) => {
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
              id: "john.doe@maif.fr",
              name : "John Doe",
              email : "john.doe@maif.fr",
              password : "",
              admin : true,
              authorizedPattern: "*"
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
            downloadLinks={[{title: "Download", link: "/api/users.ndjson"}]}
            uploadLinks={[{title: "Upload", link: "/api/users.ndjson"}]}
            showActions={true}
            showLink={false}
            eventNames={{
              created: 'USER_CREATED',
              updated: 'USER_UPDATED',
              deleted: 'USER_DELETED'
            }}
            extractKey={item => item.id} />
        </div>
      </div>
    );
  }
}