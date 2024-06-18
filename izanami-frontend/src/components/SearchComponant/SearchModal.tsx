import React from "react";
import { SearchModalContent } from "./SearchModalContent";
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
      <SearchModalContent
        tenant={tenant}
        user={user}
        onClose={() => onClose()}
      />
    </Modal>
  );
}
