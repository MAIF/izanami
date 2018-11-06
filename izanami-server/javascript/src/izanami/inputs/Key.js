import React from 'react';

const Key = props => {
  const values = props.value.split(":").filter(e => !!e);
  const size = values.length;
  return (
    <div className="btn-group btn-breadcrumb breadcrumb-info">
        {values.map( (part,i) =>
          <div className="key-value-value" key={`key-value-${props.value}-${i}`} >
            <span>{part}</span><i class="fa fa-caret-right"></i>
          </div>
        )}
    </div>
  )
};

export {Key};
