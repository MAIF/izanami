import React from 'react';

const Key = props => {
  const values = props.value.split(":").filter(e => !!e);
  const size = values.length;
  return (
    <div className="btn-group btn-breadcrumb breadcrumb-info">
        {values.map( (part,i) =>
          [
            <div className="btn btn-info key-value-value" key={`key-value-${props.value}-${i}`} >
              <span>{part}</span>
            </div>
          ])}
    </div>
  )
};

export {Key};
