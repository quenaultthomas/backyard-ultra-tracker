import { reserveAuxRoles } from '../comptes/reserve-aux-roles.guard';
import { estBenevole } from '../comptes/roles';

export const reserveAuxBenevoles = reserveAuxRoles(estBenevole);
