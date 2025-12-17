import { Tooltip } from "./Tooltip";

export function PrevisionalRightsPill({ user }: { user: string }) {
  return (
    <Tooltip
      id={user}
      iconClassName="fa-solid fa-triangle-exclamation"
      borderType="warning"
      ariaLabel="Provisional rights"
    >
      <span style={{ fontWeight: "normal" }}>
        Rights for <span style={{ fontWeight: "bold" }}>{user}</span> have been
        updated by a global right update.
        <br /> Rights displayed here are computed temporally and may change at
        user next connection.
      </span>
    </Tooltip>
  );
}
