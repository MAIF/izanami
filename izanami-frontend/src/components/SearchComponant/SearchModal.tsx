import React from "react";
import { SearchDropDown } from "./SearchDropDown";
import { Modal } from "./Modal";

export function SearchModal(props: {
  tenant: string;
  user: string;
  isOpenModal: boolean;
  onClose: () => void;
}) {
  const { tenant, user, isOpenModal, onClose } = props;
  if (!isOpenModal) return null;
  return (
    <Modal visible={isOpenModal} onClose={() => onClose()}>
      <SearchDropDown tenant={tenant} user={user} />
    </Modal>
  );
}
